package com.budu.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Budu's voice, using the phone's built-in text-to-speech.
 * [onSpeakingChanged] drives the talking mouth animation (called from a background thread).
 */
class Speaker(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: Pair<String, Locale>? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        tts.setPitch(1.3f)      // a bit higher = friendlier cartoon voice
        tts.setSpeechRate(0.9f) // a bit slower for little ears
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = onSpeakingChanged(true)
            override fun onDone(utteranceId: String?) = onSpeakingChanged(false)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onSpeakingChanged(false)
            override fun onError(utteranceId: String?, errorCode: Int) = onSpeakingChanged(false)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = onSpeakingChanged(false)
        })
        pending?.let { (text, locale) -> speak(text, locale) }
        pending = null
    }

    fun speak(text: String, locale: Locale) {
        if (!ready) {
            pending = text to locale
            return
        }
        tts.language = locale
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "budu-${System.currentTimeMillis()}")
    }

    fun stop() {
        if (ready) tts.stop()
        onSpeakingChanged(false)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
