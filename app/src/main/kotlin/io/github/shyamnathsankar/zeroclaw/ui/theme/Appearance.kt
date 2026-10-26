package io.github.shyamnathsankar.zeroclaw.ui.theme

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's light/dark choice. It drives the native palette ([Zc.dark]) and is kept in sync with the
 * dashboard's own Theme Mode, in both directions (see DashboardWebView).
 */
object Appearance {
    enum class Mode { System, Light, Dark }

    private const val PREFS = "appearance"
    private const val KEY_MODE = "mode"
    private const val KEY_SYNCED = "dashboard_scheme"

    private val _mode = MutableStateFlow(Mode.System)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    fun load(context: Context) {
        val name = prefs(context).getString(KEY_MODE, Mode.System.name) ?: Mode.System.name
        _mode.value = runCatching { Mode.valueOf(name) }.getOrDefault(Mode.System)
        apply(context)
    }

    fun set(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
        apply(context)
    }

    /** Recomputes the native palette from the mode and the system night setting. */
    fun apply(context: Context) {
        Zc.dark = resolvesDark(context)
    }

    fun resolvesDark(context: Context): Boolean = when (_mode.value) {
        Mode.Dark -> true
        Mode.Light -> false
        Mode.System -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /** The scheme ("light"/"dark") the app last wrote into the dashboard, to tell our writes from the user's. */
    fun lastSyncedScheme(context: Context): String? = prefs(context).getString(KEY_SYNCED, null)

    fun setLastSyncedScheme(context: Context, scheme: String) {
        prefs(context).edit().putString(KEY_SYNCED, scheme).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
