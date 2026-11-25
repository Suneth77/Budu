package com.budu.app

import android.app.Application
import android.graphics.Bitmap
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.budu.app.data.BuduApi
import com.budu.app.data.ChatTurn
import com.budu.app.data.Settings
import com.budu.app.data.SettingsStore
import com.budu.app.face.Character
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
    /**
     * Hands-free conversation is running: after the friend speaks, the mic opens by itself.
     * It switches off after a while of silence; tapping the face switches it back on.
     */
    val conversationOn: Boolean = false,
) {
    val busy: Boolean get() = isListening || isThinking
}

class BuduViewModel(app: Application) : AndroidViewModel(app) {

    private companion object {
        /** How many times in a row the mic may hear nothing before the friend stops listening. */
        const val MAX_SILENT_TRIES = 3

        /** Pause between the friend finishing a sentence and the mic opening (avoids hearing itself). */
        const val LISTEN_DELAY_MS = 350L
    }

    private val store = SettingsStore(app)

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val _usedMinutes = MutableStateFlow(store.usedSecondsToday() / 60)
    val usedMinutesToday: StateFlow<Long> = _usedMinutes.asStateFlow()

    // Conversation memory lives only in RAM and is forgotten when the app closes.
    private val history = ArrayDeque<ChatTurn>()

    private val speaker = Speaker(
        app,
        onSpeakingChanged = { speaking -> _ui.update { it.copy(isSpeaking = speaking) } },
        onFinished = ::onFinishedSpeaking,
    )
    private val listener = SpeechListener(app, ::onSpeechEvent)

    private var ticker: Job? = null
    private var listenWatchdog: Job? = null
    private var autoListenJob: Job? = null // waits a moment, then opens the mic
    private var voiceJob: Job? = null      // fetching a natural-voice line
    private var chatJob: Job? = null       // waiting for a reply (and maybe a picture)
    private var parentMode = false
    private var visible = false
    private var greeted = false
    private var silentTries = 0

    private val locale: Locale get() = Locale.forLanguageTag(_settings.value.languageTag)
    private val me: String get() = Character.from(_settings.value.character).displayName

    // ---------- lifecycle (called from MainActivity) ----------

    fun onAppVisible() {
        visible = true
        listener.warmUp() // connect to the speech service now, so listening starts fast
        refreshTimeLimit(announce = false)
        if (!_ui.value.timeIsUp) {
            silentTries = 0
            _ui.update { it.copy(conversationOn = true) }
            if (!greeted) {
                greeted = true
                // When the greeting ends, the mic opens by itself.
                say(Lines.greeting(_settings.value.childName, me), Emotion.EXCITED)
            } else {
                listenSoon()
            }
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
        visible = false
        ticker?.cancel()
        stopListening()
        listener.release()
        stopTalking()
    }

    override fun onCleared() {
        listener.release()
        speaker.shutdown()
    }

    // ---------- child interaction ----------

    /**
     * Tapping the face (re)starts the conversation.
     * If the friend is talking, it stops and listens right away.
     */
    fun onFaceTapped() {
        val s = _ui.value
        if (s.timeIsUp || parentMode || s.isThinking) return
        silentTries = 0
        _ui.update { it.copy(conversationOn = true) }
        if (s.isListening) return
        autoListenJob?.cancel()
        stopTalking()
        listenNow()
    }

    /** Microphone permission was just granted: start listening (after the current sentence). */
    fun onMicPermissionGranted() {
        if (_ui.value.timeIsUp || parentMode) return
        silentTries = 0
        _ui.update { it.copy(conversationOn = true) }
        if (!_ui.value.isSpeaking && voiceJob?.isActive != true) listenSoon()
    }

    /** The child tapped a different friend: switch right away and let the new friend say hi. */
    fun selectCharacter(c: Character) {
        if (c.name == _settings.value.character || parentMode) return
        chatJob?.cancel()        // the old friend's unfinished answer is dropped
        stopListening()
        stopTalking()
        saveSettings(_settings.value.copy(character = c.name)) // also clears the conversation memory
        _ui.update { it.copy(isThinking = false, isDrawing = false, picture = null) }
        if (!_ui.value.timeIsUp) {
            silentTries = 0
            _ui.update { it.copy(conversationOn = true) }
            say(Lines.greeting(_settings.value.childName, me), Emotion.EXCITED)
        }
    }

    fun closePicture() = _ui.update {
        it.copy(picture = null, emotion = if (it.isListening) Emotion.LISTENING else Emotion.HAPPY)
    }

    // ---------- the listening loop ----------

    private fun onFinishedSpeaking() {
        if (_ui.value.conversationOn) listenSoon()
    }

    private fun listenSoon() {
        autoListenJob?.cancel()
        autoListenJob = viewModelScope.launch {
            delay(LISTEN_DELAY_MS)
            listenNow()
        }
    }

    private fun listenNow() {
        val s = _ui.value
        if (!visible || parentMode || s.timeIsUp || s.busy || !s.conversationOn) return
        if (!listener.isAvailable) {
            _ui.update { it.copy(conversationOn = false, caption = "Speech recognition isn't available on this device.") }
            return
        }
        _ui.update { it.copy(isListening = true, micReady = false, emotion = Emotion.LISTENING) }
        listener.start(_settings.value.languageTag)

        // Safety net: if the speech engine never answers, count it as silence.
        listenWatchdog?.cancel()
        listenWatchdog = viewModelScope.launch {
            delay(15_000)
            if (_ui.value.isListening) {
                listener.stop()
                _ui.update { it.copy(isListening = false, micReady = false) }
                heardNothing()
            }
        }
    }

    private fun stopListening() {
        autoListenJob?.cancel()
        listenWatchdog?.cancel()
        listener.stop()
        _ui.update { it.copy(isListening = false, micReady = false) }
    }

    private fun onSpeechEvent(e: SpeechListener.Event) {
        when (e) {
            is SpeechListener.Event.Ready -> _ui.update { it.copy(micReady = true) }
            is SpeechListener.Event.Result -> {
                listenWatchdog?.cancel()
                _ui.update { it.copy(isListening = false, micReady = false) }
                if (e.text.isBlank()) {
                    heardNothing()
                } else {
                    silentTries = 0
                    ask(e.text)
                }
            }
            is SpeechListener.Event.Error -> {
                listenWatchdog?.cancel()
                _ui.update { it.copy(isListening = false, micReady = false) }
                if (e.code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    // The screen asks for the permission and calls onMicPermissionGranted().
                    _ui.update { it.copy(conversationOn = false, emotion = Emotion.HAPPY) }
                } else {
                    heardNothing()
                }
            }
            else -> Unit
        }
    }

    /** Silence (or a recognition hiccup): quietly listen again, or stop after a few tries. */
    private fun heardNothing() {
        silentTries++
        if (!_ui.value.conversationOn) {
            _ui.update { it.copy(emotion = Emotion.HAPPY) }
            return
        }
        if (silentTries < MAX_SILENT_TRIES) {
            listenSoon()
        } else {
            _ui.update { it.copy(conversationOn = false) }
            say(Lines.stillThere(), Emotion.CALM) // the mic stays off until the face is tapped
        }
    }

    // ---------- talking to the server ----------

    private fun ask(text: String) {
        val s = _settings.value
        _ui.update { it.copy(isThinking = true, emotion = Emotion.THINKING, caption = "") }
        chatJob = viewModelScope.launch {
            val api = BuduApi(s.serverUrl, s.appToken)
            try {
                val reply = api.chat(
                    message = text,
                    history = history.toList(),
                    childName = s.childName,
                    language = locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { "English" },
                    imagesEnabled = s.imagesEnabled,
                    voiceLanguage = if (s.naturalVoice) s.languageTag else null,
                    character = Character.from(s.character).serverId,
                )
                addToHistory(ChatTurn(fromChild = true, text = text))
                addToHistory(ChatTurn(fromChild = false, text = reply.reply))
                _ui.update { it.copy(isThinking = false) }
                // If the server sent no audio, the Google voice isn't set up: use the tablet voice
                // instead of asking the server again. When the reply ends, the mic opens again.
                say(reply.reply, Emotion.fromServer(reply.emotion), audio = reply.audio, tabletVoiceOnly = reply.audio == null)
                if (reply.imagePrompt != null && s.imagesEnabled) draw(api, reply.imagePrompt)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Budu", "Chat failed", e)
                // Don't keep listening if the server can't answer anyway.
                _ui.update { it.copy(isThinking = false, conversationOn = false) }
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
                    emotion = if (picture != null && !it.isListening) Emotion.EXCITED else it.emotion,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Budu", "Image failed", e)
            _ui.update { it.copy(isDrawing = false) }
        }
    }

    // ---------- speaking ----------

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
                    throw e // interrupted: say nothing
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
            stopListening()
            _ui.update {
                it.copy(timeIsUp = true, conversationOn = false, picture = null, emotion = Emotion.SLEEPY, caption = "")
            }
            if (announce) speakLine(Lines.sleepy(_settings.value.childName, me))
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
            stopListening()
            stopTalking()
        } else if (!_ui.value.timeIsUp) {
            silentTries = 0
            _ui.update { it.copy(conversationOn = true) }
            listenSoon()
        }
    }

    fun saveSettings(new: Settings) {
        // A new friend shouldn't remember what the old one talked about.
        if (new.character != _settings.value.character) history.clear()
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
 * Fixed phrases the friend says without asking the server.
 * English only for now: translate these if your child speaks another language.
 */
private object Lines {
    fun greeting(name: String, me: String) =
        if (name.isBlank()) "Hi! I'm $me! What shall we talk about?"
        else "Hi $name! I'm $me! What shall we talk about?"

    fun stillThere() = "Are you still there? Tap me when you want to talk again!"

    fun oops() = "Oops! My ears are sleepy. Can a grown-up help?"

    fun sleepy(name: String, me: String) =
        if (name.isBlank()) "Yawn... $me is sleepy now. Let's play again tomorrow! Go find your family!"
        else "Yawn... $me is sleepy now. Bye bye $name, let's play again tomorrow!"
}
