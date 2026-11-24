package com.budu.app.face

/** Faces Budu can make. The server picks the first six; the app uses the rest itself. */
enum class Emotion {
    HAPPY, EXCITED, SURPRISED, SAD, CALM, CURIOUS,
    LISTENING, THINKING, SLEEPY;

    companion object {
        fun fromServer(value: String?): Emotion = when (value?.lowercase()) {
            "happy" -> HAPPY
            "excited" -> EXCITED
            "surprised" -> SURPRISED
            "sad" -> SAD
            "calm" -> CALM
            "curious" -> CURIOUS
            else -> HAPPY
        }
    }
}
