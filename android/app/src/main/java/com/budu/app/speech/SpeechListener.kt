package com.budu.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Listens to the child once and returns what was said.
 * Uses the phone's built-in speech recognizer. Must be used from the main thread.
 *
 * Tip: toddler speech is hard for any recognizer. If results are poor, swap this
 * for a cloud model (e.g. Whisper) by recording audio and sending it to the server.
 */
class SpeechListener(
    private val context: Context,
    private val onEvent: (Event) -> Unit,
) {
    sealed interface Event {
        data object Ready : Event
        data class Level(val rmsDb: Float) : Event
        data class Result(val text: String) : Event
        data class Error(val code: Int) : Event
    }

    private var recognizer: SpeechRecognizer? = null

    val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(languageTag: String) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(Event.Ready)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = onEvent(Event.Level(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) = onEvent(Event.Error(error))
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                onEvent(Event.Result(text))
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Small children pause a lot; ask for more patience (not every engine honors this).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_000L)
        }
        recognizer = r
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }
}
