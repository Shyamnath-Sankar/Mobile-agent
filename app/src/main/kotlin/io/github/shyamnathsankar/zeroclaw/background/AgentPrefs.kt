package io.github.shyamnathsankar.zeroclaw.background

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User choices for how the agent runs in the background, plus the next scheduled wake-up. */
object AgentPrefs {
    private const val FILE = "agent_prefs"
    private const val KEY_ALWAYS_ON = "always_on"
    private const val KEY_EXACT = "exact_timing"
    private const val KEY_NEXT_WAKE = "next_wake_at"

    /** Minutes the agent keeps working after the user leaves the app. */
    const val GRACE_MINUTES = 3L

    private val _alwaysOn = MutableStateFlow(false)
    val alwaysOn: StateFlow<Boolean> = _alwaysOn.asStateFlow()

    private val _exactTiming = MutableStateFlow(false)
    val exactTiming: StateFlow<Boolean> = _exactTiming.asStateFlow()

    private val _nextWakeAt = MutableStateFlow<Long?>(null)
    val nextWakeAt: StateFlow<Long?> = _nextWakeAt.asStateFlow()

    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val p = prefs(context)
        _alwaysOn.value = p.getBoolean(KEY_ALWAYS_ON, false)
        _exactTiming.value = p.getBoolean(KEY_EXACT, false)
        _nextWakeAt.value = p.getLong(KEY_NEXT_WAKE, 0L).takeIf { it > 0 }
        loaded = true
    }

    fun setAlwaysOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ALWAYS_ON, value).apply()
        _alwaysOn.value = value
    }

    fun setExactTiming(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_EXACT, value).apply()
        _exactTiming.value = value
    }

    fun setNextWakeAt(context: Context, value: Long?) {
        prefs(context).edit().putLong(KEY_NEXT_WAKE, value ?: 0L).apply()
        _nextWakeAt.value = value
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
