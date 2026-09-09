package com.micnubinub.syncthing.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.micnubinub.syncthing.service.Constants

private val VALID_NIGHT_MODES = setOf(
    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
    AppCompatDelegate.MODE_NIGHT_NO,
    AppCompatDelegate.MODE_NIGHT_YES,
    AppCompatDelegate.MODE_NIGHT_AUTO_BATTERY,
)

abstract class ThemedAppCompatActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val prefAppTheme = sharedPreferences.getString(
            Constants.PREF_APP_THEME,
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString()
        )?.toIntOrNull()
        val nightMode = if (prefAppTheme in VALID_NIGHT_MODES) prefAppTheme
        else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        nightMode?.let {
            AppCompatDelegate.setDefaultNightMode(nightMode)
        }


        super.onCreate(savedInstanceState)
    }
}
