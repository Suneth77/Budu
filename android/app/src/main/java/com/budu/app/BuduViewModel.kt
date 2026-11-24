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
    private var parentMode = false
    private var greeted = false

    private val locale: Locale get() = Locale.forLanguageTag(_settings.value.languageTag)

    // ---------- lifecycle (called from MainActivity) ----------

    fun onAppVisible() {
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
        listener.stop()
        speaker.stop()
        _ui.update { it.copy(isListening = false) }
    }

    override fun onCleared() {
        listener.stop()
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
        speaker.stop()
        _ui.update { it.copy(isListening = true, emotion = Emotion.LISTENING, caption = "") }
        listener.start(_settings.value.languageTag)
    }

    fun closePicture() = _ui.update { it.copy(picture = null, emotion = Emotion.HAPPY) }

    private fun onSpeechEvent(e: SpeechListener.Event) {
        when (e) {
            is SpeechListener.Event.Result -> {
                _ui.update { it.copy(isListening = false) }
                if (e.text.isBlank()) didNotHear() else ask(e.text)
            }
            is SpeechListener.Event.Error -> {
                _ui.update { it.copy(isListening = false) }
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
                )
                addToHistory(ChatTurn(fromChild = true, text = text))
                addToHistory(ChatTurn(fromChild = false, text = reply.reply))
                _ui.update { it.copy(isThinking = false) }
                say(reply.reply, Emotion.fromServer(reply.emotion))
                if (reply.imagePrompt != null && s.imagesEnabled) draw(api, reply.imagePrompt)
            } catch (e: Exception) {
                Log.w("Budu", "Chat failed", e)
                _ui.update { it.copy(isThinking = false) }
                say(Lines.oops(), Emotion.SAD, caption = "Can't reach the server: ${e.message}")
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

    private fun say(text: String, emotion: Emotion, caption: String = text) {
        _ui.update { it.copy(emotion = emotion, caption = caption) }
        speaker.speak(text, locale)
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
            if (announce) speaker.speak(Lines.sleepy(_settings.value.childName), locale)
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
            speaker.stop()
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
