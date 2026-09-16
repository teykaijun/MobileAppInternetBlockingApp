package io.github.teykaijun.netblocker.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's choices: which apps are blocked and whether blocking is switched on.
 *
 * Backed by SharedPreferences so the VPN service and the boot receiver can read it synchronously,
 * and mirrored into StateFlows so the UI reacts when the service changes something (for example
 * when another VPN app takes over and blocking is switched off).
 */
class BlockerSettings private constructor(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _blockedPackages =
        MutableStateFlow(prefs.getStringSet(KEY_BLOCKED, null)?.toSet().orEmpty())
    val blockedPackages: StateFlow<Set<String>> = _blockedPackages.asStateFlow()

    private val _blockingEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    /** Whether the user wants blocking on. The tunnel only runs while this is true and at least one app is blocked. */
    val blockingEnabled: StateFlow<Boolean> = _blockingEnabled.asStateFlow()

    private val _showSystemApps = MutableStateFlow(prefs.getBoolean(KEY_SHOW_SYSTEM_APPS, false))
    val showSystemApps: StateFlow<Boolean> = _showSystemApps.asStateFlow()

    @Synchronized
    fun setBlocked(packageName: String, blocked: Boolean) {
        val current = _blockedPackages.value
        setBlockedPackages(if (blocked) current + packageName else current - packageName)
    }

    @Synchronized
    fun setBlockedPackages(packages: Set<String>) {
        prefs.edit { putStringSet(KEY_BLOCKED, packages) }
        _blockedPackages.value = packages
    }

    @Synchronized
    fun setBlockingEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_ENABLED, enabled) }
        _blockingEnabled.value = enabled
    }

    @Synchronized
    fun setShowSystemApps(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_SYSTEM_APPS, show) }
        _showSystemApps.value = show
    }

    companion object {
        private const val PREFS_NAME = "blocker_settings"
        private const val KEY_BLOCKED = "blocked_packages"
        private const val KEY_ENABLED = "blocking_enabled"
        private const val KEY_SHOW_SYSTEM_APPS = "show_system_apps"

        @Volatile
        private var instance: BlockerSettings? = null

        fun get(context: Context): BlockerSettings =
            instance ?: synchronized(this) {
                instance ?: BlockerSettings(context.applicationContext).also { instance = it }
            }
    }
}
