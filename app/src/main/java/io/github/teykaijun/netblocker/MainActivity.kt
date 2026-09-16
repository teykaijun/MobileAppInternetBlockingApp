package io.github.teykaijun.netblocker

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.teykaijun.netblocker.ui.BlockerScreen
import io.github.teykaijun.netblocker.ui.MainViewModel
import io.github.teykaijun.netblocker.ui.theme.NetBlockerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            NetBlockerTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val snackbarHostState = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                val showMessage: (Int) -> Unit = { message ->
                    scope.launch { snackbarHostState.showSnackbar(getString(message)) }
                }

                val notificationPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* The tunnel runs either way; without it the status notification just stays hidden. */ }

                val vpnPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        viewModel.turnOn()
                        requestNotificationPermission(notificationPermission)
                    } else {
                        showMessage(R.string.vpn_permission_denied)
                    }
                }

                BlockerScreen(
                    state = state,
                    query = viewModel.query,
                    snackbarHostState = snackbarHostState,
                    onQueryChange = viewModel::onQueryChange,
                    onBlockingChange = { enabled ->
                        if (!enabled) {
                            viewModel.turnOff()
                            return@BlockerScreen
                        }
                        // Android shows its own consent dialog the first time, and again if
                        // another VPN app has taken over since.
                        val consent = VpnService.prepare(this)
                        if (consent == null) {
                            viewModel.turnOn()
                            requestNotificationPermission(notificationPermission)
                        } else {
                            try {
                                vpnPermission.launch(consent)
                            } catch (e: ActivityNotFoundException) {
                                showMessage(R.string.vpn_not_supported)
                            }
                        }
                    },
                    onAppBlockedChange = viewModel::setBlocked,
                    onBlockedOnlyChange = viewModel::setBlockedOnly,
                    onShowSystemAppsChange = viewModel::setShowSystemApps,
                    onUnblockAll = viewModel::unblockAll,
                    onOpenVpnSettings = {
                        try {
                            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        } catch (e: ActivityNotFoundException) {
                            showMessage(R.string.vpn_settings_unavailable)
                        }
                    },
                    onOpenSupport = {
                        // Opens in the browser, so this app still needs no INTERNET permission.
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, SUPPORT_URL.toUri()))
                        } catch (e: ActivityNotFoundException) {
                            showMessage(R.string.no_browser)
                        }
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Picks up apps installed meanwhile, and notices if the VPN permission was taken away.
        viewModel.onResume()
    }

    private fun requestNotificationPermission(launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val SUPPORT_URL = "https://buymeacoffee.com/casunoxd"
    }
}
