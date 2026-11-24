package com.budu.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens to the child once and returns what was said.
 * Uses the phone's built-in speech recognizer. Must be used from the main thread.
 *
 * Speed notes:
 * - ONE recognizer is created and reused. Creating a new one on every tap is slow
 *   (it has to connect to the Google speech service each time).
 * - "Busy" errors (common right after stopping) are retried once instead of
 *   being treated as "didn't hear you".
 * - Events from a session that was already cancelled are ignored.
 *
 * Tip: toddler speech is hard for any recognizer. If results are poor, swap this
 * for a cloud model (e.g. Whisper) by recording audio and sending it to the server.
 */
class SpeechListener(
    private val context: Context,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        /** The mic is open: now is the moment for the child to talk. */
        data object Ready : Event
        data class Level(val rmsDb: Float) : Event
        data class Result(val text: String) : Event
        data class Error(val code: Int) : Event
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var retried = false
    private var languageTag = "en-US"

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    /** Call early (e.g. when the screen opens) so the first tap is fast too. */
    fun warmUp() {
        if (recognizer == null && isAvailable) recognizer = create()
    }

    fun start(languageTag: String) {
        this.languageTag = languageTag
        retried = false
        main.removeCallbacksAndMessages(null)
        val r = recognizer ?: create().also { recognizer = it }
        r.cancel() // stop any previous session, but keep the connection
        active = true
        r.startListening(intent())
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
    }

    /** Free the recognizer completely (call when the app closes). */
    fun release() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun create(): SpeechRecognizer =
        SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (active) onEvent(Event.Ready)
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    if (active) onEvent(Event.Level(rmsdB))
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {
                    if (!active) return // stale error from a cancelled session
                    if (!retried && (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                            error == SpeechRecognizer.ERROR_CLIENT)
                    ) {
                        retried = true
                        recognizer?.cancel()
                        main.postDelayed({ if (active) recognizer?.startListening(intent()) }, 250)
                        return
                    }
                    active = false
                    onEvent(Event.Error(error))
                }
                override fun onResults(results: Bundle?) {
                    if (!active) return
                    active = false
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    onEvent(Event.Result(text))
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Small children pause a lot; ask for more patience (not every engine honors this).
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
    }
}
