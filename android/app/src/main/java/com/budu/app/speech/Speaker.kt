package com.budu.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Budu's voice.
 * - [playAudio] plays the natural Google voice (an MP3 made by the server).
 * - [speak] uses the tablet's built-in text-to-speech; it's also the automatic
 *   backup if the MP3 can't be played.
 * [onSpeakingChanged] drives the talking mouth animation.
 */
class Speaker(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit,
) : TextToSpeech.OnInitListener {

    private val appContext = context.applicationContext
    private val tts = TextToSpeech(appContext, this)
    private var ready = false
    private var pending: Pair<String, Locale>? = null
    private var player: MediaPlayer? = null
    private val voiceFile = File(appContext.cacheDir, "budu_voice.mp3")

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (!ready) return
        tts.setPitch(1.3f)      // a bit higher = friendlier cartoon voice (tablet voice only)
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

    /** Speak with the tablet's built-in voice. */
    fun speak(text: String, locale: Locale) {
        releasePlayer()
        if (!ready) {
            pending = text to locale
            return
        }
        tts.language = locale
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "budu-${System.currentTimeMillis()}")
    }

    /** Play the natural voice MP3. Falls back to [speak] with [fallbackText] if playback fails. */
    fun playAudio(mp3: ByteArray, fallbackText: String, locale: Locale) {
        stop()
        try {
            voiceFile.writeBytes(mp3)
            val p = MediaPlayer()
            player = p
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            p.setDataSource(voiceFile.absolutePath)
            p.setOnPreparedListener {
                it.start()
                onSpeakingChanged(true)
            }
            p.setOnCompletionListener {
                releasePlayer()
                onSpeakingChanged(false)
            }
            p.setOnErrorListener { _, what, extra ->
                Log.w("Budu", "Voice playback failed ($what/$extra), using tablet voice")
                releasePlayer()
                onSpeakingChanged(false)
                speak(fallbackText, locale)
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            Log.w("Budu", "Voice playback failed, using tablet voice", e)
            releasePlayer()
            speak(fallbackText, locale)
        }
    }

    fun stop() {
        pending = null
        releasePlayer()
        if (ready) tts.stop()
        onSpeakingChanged(false)
    }

    fun shutdown() {
        releasePlayer()
        tts.stop()
        tts.shutdown()
    }

    private fun releasePlayer() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // wasn't playing yet
            }
            it.release()
        }
        player = null
    }
}
