package io.github.teykaijun.netblocker.ui

import android.app.Application
import android.net.VpnService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.teykaijun.netblocker.data.BlockerSettings
import io.github.teykaijun.netblocker.data.InstalledApp
import io.github.teykaijun.netblocker.data.InstalledApps
import io.github.teykaijun.netblocker.vpn.BlockerVpnService
import io.github.teykaijun.netblocker.vpn.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppRow(val app: InstalledApp, val blocked: Boolean)

data class MainUiState(
    val loadingApps: Boolean = true,
    val rows: List<AppRow> = emptyList(),
    val blockedOnly: Boolean = false,
    val showSystemApps: Boolean = false,
    /** Blocked apps that are currently installed. */
    val blockedCount: Int = 0,
    val blockingEnabled: Boolean = false,
    val tunnel: TunnelState = TunnelState.Stopped,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = BlockerSettings.get(application)
    private val installedApps = MutableStateFlow<List<InstalledApp>?>(null)
    private val blockedOnly = MutableStateFlow(false)
    private var loadJob: Job? = null

    /** Search text, kept in Compose state so the text field never lags behind typing. */
    var query by mutableStateOf("")
        private set

    private val filters = combine(snapshotFlow { query }, blockedOnly, settings.showSystemApps) { query, blockedOnly, showSystem ->
        Filters(query.trim(), blockedOnly, showSystem)
    }

    val uiState: StateFlow<MainUiState> = combine(
        installedApps,
        filters,
        settings.blockedPackages,
        settings.blockingEnabled,
        BlockerVpnService.state,
    ) { apps, filters, blocked, enabled, tunnel ->
        MainUiState(
            loadingApps = apps == null,
            rows = apps.orEmpty()
                .filter { filters.matches(it, blocked) }
                .map { AppRow(it, it.packageName in blocked) },
            blockedOnly = filters.blockedOnly,
            showSystemApps = filters.showSystemApps,
            blockedCount = apps?.count { it.packageName in blocked } ?: blocked.size,
            blockingEnabled = enabled,
            tunnel = tunnel,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun onQueryChange(value: String) {
        query = value
    }

    fun setBlockedOnly(value: Boolean) {
        blockedOnly.value = value
    }

    fun setShowSystemApps(value: Boolean) = settings.setShowSystemApps(value)

    fun setBlocked(packageName: String, blocked: Boolean) {
        settings.setBlocked(packageName, blocked)
        BlockerVpnService.sync(getApplication())
    }

    fun unblockAll() {
        settings.setBlockedPackages(emptySet())
        BlockerVpnService.sync(getApplication())
    }

    /** Turns blocking on. The caller must have obtained the VPN permission first. */
    fun turnOn() {
        settings.setBlockingEnabled(true)
        BlockerVpnService.sync(getApplication())
    }

    fun turnOff() {
        settings.setBlockingEnabled(false)
        BlockerVpnService.sync(getApplication())
    }

    /** Re-checks everything that may have changed while the app was in the background. */
    fun onResume() {
        if (settings.blockingEnabled.value && VpnService.prepare(getApplication()) != null) {
            // Another VPN app took over while no tunnel was running, so blocking can't continue.
            settings.setBlockingEnabled(false)
        }
        BlockerVpnService.sync(getApplication())
        reloadApps()
    }

    private fun reloadApps() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            installedApps.value = withContext(Dispatchers.IO) { InstalledApps.load(getApplication()) }
        }
    }

    private data class Filters(val query: String, val blockedOnly: Boolean, val showSystemApps: Boolean) {
        fun matches(app: InstalledApp, blocked: Set<String>): Boolean {
            val isBlocked = app.packageName in blocked
            if (blockedOnly && !isBlocked) return false
            // Background system components are hidden unless requested, but blocked ones always stay visible.
            if (!showSystemApps && app.isSystem && !app.hasLauncherIcon && !isBlocked) return false
            return query.isEmpty() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }
    }
}
