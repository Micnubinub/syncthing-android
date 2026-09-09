package com.micnubinub.syncthing.settings

import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.navigation3.runtime.EntryProviderScope
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.navigation.LocalSyncthingService
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.LocalActivityScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.ListPreference
import me.zhanghai.compose.preference.SwitchPreference
import me.zhanghai.compose.preference.rememberPreferenceState


private const val TAG = "SettingsUserInterfaceScreen"

fun EntryProviderScope<SettingsRoute>.settingsUserInterfaceEntry() {
    entry<SettingsRoute.UserInterface> {
        SettingsUserInterfaceScreen()
    }
}


@Composable
fun SettingsUserInterfaceScreen() {
    val context = LocalContext.current
    val scope = LocalActivityScope.current
    val stService = LocalSyncthingService.current

    val themeNames = stringArrayResource(R.array.app_theme_names)
    val themeValues = stringArrayResource(R.array.app_theme_values)

    // Resolve a stored pref value to its display name, falling back to the
    // default entry when the value is unknown (e.g. crafted/corrupt prefs) to
    // avoid an ArrayIndexOutOfBoundsException from indexOf() == -1.
    fun themeNameFor(value: String?): String {
        val fallbackIndex = themeValues.indexOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString()
        ).takeIf { it >= 0 } ?: 0
        val index = themeValues.indexOf(value).takeIf { it >= 0 } ?: fallbackIndex
        return themeNames.getOrElse(index) { themeNames[fallbackIndex] }
    }

    var theme by rememberPreferenceState(
        Constants.PREF_APP_THEME,
        AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM.toString()
    )
    val expertMode = rememberPreferenceState(Constants.PREF_EXPERT_MODE, false)
    val startInWebGui = rememberPreferenceState(Constants.PREF_START_INTO_WEB_GUI, false)

    SettingsScaffold(
        title = stringResource(R.string.category_user_interface),
    ) {
        item {
            ListPreference(
                title = { Text(stringResource(R.string.preference_app_theme_title)) },
                summary = { Text(themeNameFor(theme)) },
                value = theme,
                onValueChange = { newTheme ->
                    val newThemeInt = newTheme.toIntOrNull()
                    if (newTheme != theme && newThemeInt != null) {
                        theme = newTheme
                        AppCompatDelegate.setDefaultNightMode(newThemeInt)
                        scope.launch(Dispatchers.IO) {
                            withContext(NonCancellable) {
                                try {
                                    val config = ConfigRouter(context)
                                    stService?.api?.let {
                                        config.getGui(it)?.let { gui ->
                                            gui.theme = when (newTheme) {
                                                AppCompatDelegate.MODE_NIGHT_YES.toString() -> "dark"
                                                AppCompatDelegate.MODE_NIGHT_NO.toString() -> "light"
                                                else -> "default"
                                            }
                                            config.updateGui(it, gui)
                                        }
                                    }

                                } catch (e: Exception) {
                                    Log.e(TAG, "Error updating theme with config router", e)
                                }
                            }
                        }
                    }
                },
                values = themeValues.toList(),
                valueToText = { value -> AnnotatedString(themeNameFor(value)) }
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.expert_mode_title)) },
                summary = { Text(stringResource(R.string.expert_mode_summary)) },
                state = expertMode,
            )
        }
        item {
            SwitchPreference(
                title = { Text(stringResource(R.string.start_into_web_gui_title)) },
                summary = { Text(stringResource(R.string.start_into_web_gui_summary)) },
                state = startInWebGui,
            )
        }
    }
}
