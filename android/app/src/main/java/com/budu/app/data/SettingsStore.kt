package com.budu.app.data

import android.content.Context
import java.time.LocalDate

/** Everything a parent can change. Stored only on this device. */
data class Settings(
    val pin: String = "1234",
    val childName: String = "",
    val languageTag: String = "en-US",       // e.g. en-US, et-EE, ru-RU
    val dailyLimitMinutes: Int = 20,         // 0 = no limit
    val imagesEnabled: Boolean = true,
    val serverUrl: String = "http://10.0.2.2:8000", // 10.0.2.2 = your computer, seen from the emulator
    val appToken: String = "",
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("budu_parent", Context.MODE_PRIVATE)

    fun load(): Settings {
        val d = Settings()
        return Settings(
            pin = prefs.getString("pin", d.pin) ?: d.pin,
            childName = prefs.getString("child_name", d.childName) ?: d.childName,
            languageTag = prefs.getString("language", d.languageTag) ?: d.languageTag,
            dailyLimitMinutes = prefs.getInt("daily_limit", d.dailyLimitMinutes),
            imagesEnabled = prefs.getBoolean("images", d.imagesEnabled),
            serverUrl = prefs.getString("server_url", d.serverUrl) ?: d.serverUrl,
            appToken = prefs.getString("app_token", d.appToken) ?: d.appToken,
        )
    }

    fun save(s: Settings) {
        prefs.edit()
            .putString("pin", s.pin)
            .putString("child_name", s.childName)
            .putString("language", s.languageTag)
            .putInt("daily_limit", s.dailyLimitMinutes)
            .putBoolean("images", s.imagesEnabled)
            .putString("server_url", s.serverUrl)
            .putString("app_token", s.appToken)
            .apply()
    }

    // ----- daily play time -----

    fun usedSecondsToday(): Long {
        val today = LocalDate.now().toString()
        return if (prefs.getString("usage_date", null) == today) prefs.getLong("used_seconds", 0L) else 0L
    }

    fun addUsage(seconds: Long) {
        val total = usedSecondsToday() + seconds
        prefs.edit()
            .putString("usage_date", LocalDate.now().toString())
            .putLong("used_seconds", total)
            .apply()
    }

    fun resetUsageToday() {
        prefs.edit()
            .putString("usage_date", LocalDate.now().toString())
            .putLong("used_seconds", 0L)
            .apply()
    }
}
