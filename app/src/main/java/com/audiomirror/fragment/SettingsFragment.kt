package com.audiomirror.fragment

import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.audiomirror.App
import com.audiomirror.R

class SettingsFragment : PreferenceFragmentCompat() {

    private var isListener = false
    private var isStreaming = false

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        isListener = arguments?.getBoolean(ARG_IS_LISTENER, false) ?: false
        isStreaming = arguments?.getBoolean(ARG_IS_STREAMING, false) ?: false

        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<ListPreference>("audio_mode")?.apply {
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            isEnabled = !isListener && !isStreaming
        }

        findPreference<ListPreference>("app_theme")?.apply {
            summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
            setOnPreferenceChangeListener { _, newValue ->
                // Save the new value FIRST, then apply, then recreate
                // This fixes the "need to select twice" bug
                PreferenceManager.getDefaultSharedPreferences(requireContext())
                    .edit().putString("app_theme", newValue.toString()).apply()
                App.applyTheme(requireContext())
                activity?.recreate()
                false // return false because we already saved it manually
            }
        }
    }

    companion object {
        private const val ARG_IS_LISTENER = "is_listener"
        private const val ARG_IS_STREAMING = "is_streaming"

        fun newInstance(isListener: Boolean, isStreaming: Boolean) = SettingsFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_IS_LISTENER, isListener)
                putBoolean(ARG_IS_STREAMING, isStreaming)
            }
        }
    }
}
