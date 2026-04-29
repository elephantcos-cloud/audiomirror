package com.audiomirror

import android.app.Application
import android.content.Context
import androidx.preference.PreferenceManager

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        applyTheme(this)
    }

    companion object {
        private lateinit var instance: App
        fun get(): Context = instance

        fun applyTheme(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val theme = prefs.getString("app_theme", "dark") ?: "dark"
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (theme == "light")
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                else
                    androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            )
        }
    }
}
