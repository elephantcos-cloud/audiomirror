package com.audiomirror.activity

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.audiomirror.App
import com.audiomirror.R
import com.audiomirror.fragment.SettingsFragment

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        App.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        val isListener = intent.getBooleanExtra(EXTRA_IS_LISTENER, false)
        val isStreaming = intent.getBooleanExtra(EXTRA_IS_STREAMING, false)

        if (isListener) {
            Toast.makeText(this, R.string.settings_listener_locked, Toast.LENGTH_LONG).show()
        } else if (isStreaming) {
            Toast.makeText(this, R.string.settings_streaming_locked, Toast.LENGTH_LONG).show()
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.settings_container, SettingsFragment.newInstance(isListener, isStreaming))
            .commit()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    companion object {
        const val EXTRA_IS_LISTENER = "is_listener"
        const val EXTRA_IS_STREAMING = "is_streaming"
    }
}
