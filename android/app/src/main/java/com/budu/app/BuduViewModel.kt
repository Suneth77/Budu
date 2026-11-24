package com.budu.app

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.budu.app.data.BuduApi
import com.budu.app.data.ChatTurn
import com.budu.app.data.Settings
import com.budu.app.data.SettingsStore
import com.budu.app.face.Emotion
import com.budu.app.speech.SpeechListener
import com.budu.app.speech.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

data class UiState(
    val emotion: Emotion = Emotion.HAPPY,
    val isListening: Boolean = false,
    val micReady: Boolean = false,      // true once the mic is really open
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val isDrawing: Boolean = false,
    val caption: String = "",
    val picture: Bitmap? = null,
    val timeIsUp: Boolean = false,
) {
    val busy: Boolean get() = isListening || isThinking
}

class BuduViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SettingsStore(app)

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _usedMinutes = MutableStateFlow(store.usedSecondsToday() / 60)
    val usedMinutesToday: StateFlow<Long> = _usedMinutes.asStateFlow()

    // Conversation memory lives only in RAM and is forgotten when the app closes.
    private val history = ArrayDeque<ChatTurn>()

    private val speaker = Speaker(app) { speaking -> _ui.update { it.copy(isSpeaking = speaking) } }
    private val listener = SpeechListener(app, ::onSpeechEvent)

    private var ticker: Job? = null
    private var listenWatchdog: Job? = null
    private var voiceJob: Job? = null // fetching a natural-voice line
    private var parentMode = false
    private var greeted = false

    private val locale: Locale get() = Locale.forLanguageTag(_settings.value.languageTag)

    // ---------- lifecycle (called from MainActivity) ----------

    fun onAppVisible() {
        listener.warmUp() // connect to the speech service now, so the first tap is fast
        refreshTimeLimit(announce = false)
        if (!greeted && !_ui.value.timeIsUp) {
            greeted = true
            say(Lines.greeting(_settings.value.childName), Emotion.EXCITED)
        }
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(10_000)
                if (!parentMode) store.addUsage(10)
                _usedMinutes.value = store.usedSecondsToday() / 60
                refreshTimeLimit(announce = true)
            }
        }
    }

    fun onAppHidden() {
        ticker?.cancel()
        listenWatchdog?.cancel()
        listener.release()
        stopTalking()
        _ui.update { it.copy(isListening = false, micReady = false) }
    }

    override fun onCleared() {
        listener.release()
        speaker.shutdown()
    }

    // ---------- child interaction ----------

    fun onTalkTapped() {
        val s = _ui.value
        if (s.timeIsUp || s.busy || parentMode) return
        if (!listener.isAvailable) {
            _ui.update { it.copy(caption = "Speech recognition isn't available on this device.") }
            return
        }
        stopTalking()
        _ui.update { it.copy(isListening = true, micReady = false, emotion = Emotion.LISTENING, caption = "") }
        listener.start(_settings.value.languageTag)

        // Safety net: if the speech engine never answers, don't leave the button stuck.
        listenWatchdog?.cancel()
        listenWatchdog = viewModelScope.launch {
            delay(15_000)
            if (_ui.value.isListening) {
                listener.stop()
                _ui.update { it.copy(isListening = false, micReady = false) }
                didNotHear()
            }
        }
    }

    fun closePicture() = _ui.update { it.copy(picture = null, emotion = Emotion.HAPPY) }

    private fun onSpeechEvent(e: SpeechListener.Event) {
        when (e) {
            is SpeechListener.Event.Ready -> _ui.update { it.copy(micReady = true) }
            is SpeechListener.Event.Result -> {
                listenWatchdog?.cancel()
                _ui.update { it.copy(isListening = false, micReady = false) }
                if (e.text.isBlank()) didNotHear() else ask(e.text)
            }
            is SpeechListener.Event.Error -> {
                listenWatchdog?.cancel()
                _ui.update { it.copy(isListening = false, micReady = false) }
                didNotHear()
            }
            else -> Unit
        }
    }

    private fun didNotHear() = say(Lines.didNotHear(), Emotion.CURIOUS)

    private fun ask(text: String) {
        val s = _settings.value
        _ui.update { it.copy(isThinking = true, emotion = Emotion.THINKING, caption = "") }
        viewModelScope.launch {
            val api = BuduApi(s.serverUrl, s.appToken)
            try {
                val reply = api.chat(
                    message = text,
                    history = history.toList(),
                    childName = s.childName,
                    language = locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" },
                    imagesEnabled = s.imagesEnabled,
                    voiceLanguage = if (s.naturalVoice) s.languageTag else null,
                )
                addToHistory(ChatTurn(fromChild = true, text = text))
                addToHistory(ChatTurn(fromChild = false, text = reply.reply))
                _ui.update { it.copy(isThinking = false) }
                // If the server sent no audio, the Google voice isn't set up: use the tablet voice
                // instead of asking the server again.
                say(reply.reply, Emotion.fromServer(reply.emotion), audio = reply.audio, tabletVoiceOnly = reply.audio == null)
                if (reply.imagePrompt != null && s.imagesEnabled) draw(api, reply.imagePrompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Budu", "Chat failed", e)
                _ui.update { it.copy(isThinking = false) }
                // Server unreachable, so it can't make the natural voice either.
                say(Lines.oops(), Emotion.SAD, caption = "Can't reach the server: ${e.message}", tabletVoiceOnly = true)
            }
        }
    }

    private suspend fun draw(api: BuduApi, prompt: String) {
        _ui.update { it.copy(isDrawing = true) }
        try {
            val picture = api.image(prompt)
            _ui.update {
                it.copy(
                    isDrawing = false,
                    picture = picture ?: it.picture,
                    emotion = if (picture != null) Emotion.EXCITED else it.emotion,
                )
            }
        } catch (e: Exception) {
            Log.w("Budu", "Image failed", e)
            _ui.update { it.copy(isDrawing = false) }
        }
    }

    /**
     * Show [emotion] and say [text]:
     * - [audio] given: play it (natural voice, already made by the server)
     * - natural voice on: ask the server for it, fall back to the tablet voice
     * - otherwise: tablet voice
     */
    private fun say(
        text: String,
        emotion: Emotion,
        caption: String = text,
        audio: ByteArray? = null,
        tabletVoiceOnly: Boolean = false,
    ) {
        _ui.update { it.copy(emotion = emotion, caption = caption) }
        speakLine(text, audio, tabletVoiceOnly)
    }

    private fun speakLine(text: String, audio: ByteArray? = null, tabletVoiceOnly: Boolean = false) {
        voiceJob?.cancel()
        val s = _settings.value
        val loc = locale
        when {
            audio != null -> speaker.playAudio(audio, text, loc)
            tabletVoiceOnly || !s.naturalVoice -> speaker.speak(text, loc)
            else -> voiceJob = viewModelScope.launch {
                val mp3 = try {
                    BuduApi(s.serverUrl, s.appToken).speak(text, s.languageTag)
                } catch (e: CancellationException) {
                    throw e // Budu was interrupted: say nothing
                } catch (e: Exception) {
                    Log.w("Budu", "Natural voice unavailable", e)
                    null
                }
                if (mp3 != null) speaker.playAudio(mp3, text, loc) else speaker.speak(text, loc)
            }
        }
    }

    private fun stopTalking() {
        voiceJob?.cancel()
        speaker.stop()
    }

    private fun addToHistory(turn: ChatTurn) {
        history.addLast(turn)
        while (history.size > 12) history.removeFirst()
    }

    // ---------- daily time limit ----------

    private fun isTimeUp(): Boolean {
        val limit = _settings.value.dailyLimitMinutes
        return limit > 0 && store.usedSecondsToday() >= limit * 60L
    }

    private fun refreshTimeLimit(announce: Boolean) {
        val up = isTimeUp()
        val was = _ui.value.timeIsUp
        if (up && !was) {
            listener.stop()
            _ui.update {
                it.copy(timeIsUp = true, isListening = false, picture = null, emotion = Emotion.SLEEPY, caption = "")
            }
            if (announce) speakLine(Lines.sleepy(_settings.value.childName))
        } else if (!up && was) {
            _ui.update { it.copy(timeIsUp = false, emotion = Emotion.HAPPY) }
        } else if (up) {
            _ui.update { it.copy(emotion = Emotion.SLEEPY) }
        }
    }

    // ---------- parent area ----------

    fun setParentMode(enabled: Boolean) {
        parentMode = enabled
        if (enabled) {
            listener.stop()
            stopTalking()
            _ui.update { it.copy(isListening = false) }
        }
    }

    fun saveSettings(new: Settings) {
        store.save(new)
        _settings.value = new
        refreshTimeLimit(announce = false)
    }

    fun resetTodayUsage() {
        store.resetUsageToday()
        _usedMinutes.value = 0
        refreshTimeLimit(announce = false)
    }
}

/**
 * Fixed phrases Budu says without asking the server.
 * English only for now: translate these if your child speaks another language.
 */
private object Lines {
    fun greeting(name: String) =
        if (name.isBlank()) "Hi! I'm Budu! Tap me and tell me something!"
        else "Hi $name! I'm Budu! Tap me and tell me something!"

    fun didNotHear() = "Hmm? I didn't hear you. Tap me and try again!"

    fun oops() = "Oops! My ears are sleepy. Can a grown-up help?"

    fun sleepy(name: String) =
        if (name.isBlank()) "Yawn... Budu is sleepy now. Let's play again tomorrow! Go find your family!"
        else "Yawn... Budu is sleepy now. Bye bye $name, let's play again tomorrow!"
}
