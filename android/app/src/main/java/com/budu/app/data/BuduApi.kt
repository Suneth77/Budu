package com.budu.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val fromChild: Boolean, val text: String)

/** [audio] is an MP3 of the reply in the natural Google voice, or null. */
class BuduReply(val reply: String, val emotion: String, val imagePrompt: String?, val audio: ByteArray?)

/** Talks to the Budu server (never directly to the AI services). */
class BuduApi(private val baseUrl: String, private val appToken: String) {

    suspend fun chat(
        message: String,
        history: List<ChatTurn>,
        childName: String,
        language: String,
        imagesEnabled: Boolean,
        voiceLanguage: String?, // e.g. "et-EE" to also get the reply as audio; null = no audio
    ): BuduReply = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("message", message)
            put("history", JSONArray().apply {
                history.forEach { turn ->
                    put(JSONObject().put("role", if (turn.fromChild) "child" else "budu").put("text", turn.text))
                }
            })
            if (childName.isNotBlank()) put("child_name", childName)
            put("language", language)
            put("images_enabled", imagesEnabled)
            if (voiceLanguage != null) put("voice_language", voiceLanguage)
        }
        val json = post("/chat", body, readTimeoutMs = 30_000)
        BuduReply(
            reply = json.getString("reply"),
            emotion = json.optString("emotion", "happy"),
            imagePrompt = if (json.isNull("image_prompt")) null else json.optString("image_prompt").ifBlank { null },
            audio = json.base64OrNull("audio_base64"),
        )
    }

    /** Budu's fixed lines in the natural voice. Returns null if the voice isn't set up. */
    suspend fun speak(text: String, languageTag: String): ByteArray? = withContext(Dispatchers.IO) {
        val json = post("/speak", JSONObject().put("text", text).put("language", languageTag), readTimeoutMs = 20_000)
        json.base64OrNull("audio_base64")
    }

    private fun JSONObject.base64OrNull(key: String): ByteArray? =
        if (!has(key) || isNull(key)) null else Base64.decode(getString(key), Base64.DEFAULT)

    /** Returns null if the server declined or couldn't draw it. */
    suspend fun image(prompt: String): Bitmap? = withContext(Dispatchers.IO) {
        val json = post("/image", JSONObject().put("prompt", prompt), readTimeoutMs = 120_000)
        if (json.isNull("image_base64")) return@withContext null
        val bytes = Base64.decode(json.getString("image_base64"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun post(path: String, body: JSONObject, readTimeoutMs: Int): JSONObject {
        val conn = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (appToken.isNotBlank()) setRequestProperty("X-App-Token", appToken)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IOException("Server error $code: $text")
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
