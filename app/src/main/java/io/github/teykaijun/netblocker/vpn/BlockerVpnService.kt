package io.github.teykaijun.netblocker.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.teykaijun.netblocker.MainActivity
import io.github.teykaijun.netblocker.R
import io.github.teykaijun.netblocker.data.BlockerSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Blocks internet access for the apps the user selected.
 *
 * The service establishes a local VPN interface that only the blocked apps are routed into
 * ([VpnService.Builder.addAllowedApplication]); every other app keeps using the normal network.
 * Nothing that enters the tunnel ever leaves the device: [PacketLoop] refuses TCP and UDP
 * immediately and drops everything else.
 *
 * All state below is only touched on the main thread.
 */
class BlockerVpnService : VpnService() {

    private lateinit var settings: BlockerSettings
    private val mainHandler = Handler(Looper.getMainLooper())

    private var packetLoop: PacketLoop? = null
    private var tunnelPackages: Set<String> = emptySet()
    private var lastUnexpectedStop = 0L

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val packageName = intent.data?.schemeSpecificPart ?: return
            // A blocked app was reinstalled and got a new user ID: rebuild the tunnel so it stays blocked.
            if (packetLoop != null && packageName in settings.blockedPackages.value) {
                applyConfiguration(forceRebuild = true)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = BlockerSettings.get(this)
        createNotificationChannel()
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TURN_OFF -> settings.setBlockingEnabled(false)
            // The system starts the service with this action when it is the always-on VPN.
            SERVICE_INTERFACE -> settings.setBlockingEnabled(true)
        }
        // ACTION_APPLY, the actions above, or a null intent when the system restarts this sticky service.
        applyConfiguration()
        return START_STICKY
    }

    override fun onRevoke() {
        // Another VPN app was started, or the user disconnected this VPN in system settings.
        settings.setBlockingEnabled(false)
        shutDown(TunnelState.Failed(FailureReason.PERMISSION_REVOKED))
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        packetLoop?.shutdown()
        packetLoop = null
        if (_state.value is TunnelState.Running) _state.value = TunnelState.Stopped
        super.onDestroy()
    }

    /** Starts, rebuilds or stops the tunnel so it matches the saved settings. */
    private fun applyConfiguration(forceRebuild: Boolean = false) {
        if (VpnService.prepare(this) != null) {
            // The VPN permission was never granted or has been revoked since.
            val wanted = settings.blockingEnabled.value
            settings.setBlockingEnabled(false)
            shutDown(if (wanted) TunnelState.Failed(FailureReason.PERMISSION_REVOKED) else TunnelState.Stopped)
            return
        }

        val blocked = installedBlockedPackages()
        // startForegroundService() requires entering the foreground, even if the tunnel stops right away.
        if (!enterForeground(blocked.size)) {
            shutDown(TunnelState.Failed(FailureReason.START_FAILED))
            return
        }
        if (!settings.blockingEnabled.value || blocked.isEmpty()) {
            shutDown(TunnelState.Stopped)
            return
        }
        if (!forceRebuild && packetLoop != null && blocked == tunnelPackages) {
            _state.value = TunnelState.Running(blocked.size)
            return
        }

        val loop = try {
            buildTunnel(blocked)?.let { PacketLoop(it, ::onPacketLoopDied) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not establish the tunnel", e)
            null
        }
        if (loop == null) {
            shutDown(TunnelState.Failed(FailureReason.START_FAILED))
            return
        }

        // Start the new tunnel before closing the old one so blocked apps are never let through.
        val previous = packetLoop
        packetLoop = loop
        loop.start()
        previous?.shutdown()
        tunnelPackages = blocked
        _state.value = TunnelState.Running(blocked.size)
    }

    private fun buildTunnel(packages: Set<String>): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession(getString(R.string.vpn_session_name))
            .setConfigureIntent(openAppIntent())
            .setMtu(MTU)
            .setBlocking(false)
            // Capture all IPv4 and IPv6 traffic, including DNS, of the allowed (= blocked) apps.
            .addAddress(TUNNEL_ADDRESS_V4, 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(TUNNEL_DNS_V4)
            .addAddress(TUNNEL_ADDRESS_V6, 128)
            .addRoute("::", 0)
            .addDnsServer(TUNNEL_DNS_V6)

        var allowed = 0
        for (packageName in packages) {
            try {
                builder.addAllowedApplication(packageName)
                allowed++
            } catch (e: PackageManager.NameNotFoundException) {
                Log.i(TAG, "Skipping $packageName: no longer installed")
            }
        }
        // Never establish a tunnel without an allow-list: it would cut off every app on the device.
        if (allowed == 0) return null
        return builder.establish()
    }

    private fun installedBlockedPackages(): Set<String> =
        settings.blockedPackages.value.filterTo(HashSet()) { packageName ->
            packageName != this.packageName && try {
                packageManager.getApplicationInfo(packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
        }

    private fun onPacketLoopDied(loop: PacketLoop) {
        mainHandler.post {
            if (loop !== packetLoop) return@post // an old tunnel that was already replaced or stopped
            packetLoop = null
            val now = SystemClock.elapsedRealtime()
            if (now - lastUnexpectedStop < RESTART_BACKOFF_MS) {
                shutDown(TunnelState.Failed(FailureReason.START_FAILED))
            } else {
                lastUnexpectedStop = now
                applyConfiguration(forceRebuild = true)
            }
        }
    }

    private fun shutDown(finalState: TunnelState) {
        packetLoop?.shutdown()
        packetLoop = null
        tunnelPackages = emptySet()
        _state.value = finalState
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun enterForeground(blockedCount: Int): Boolean = try {
        val notification = buildNotification(blockedCount)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: RuntimeException) {
        Log.e(TAG, "Could not start the foreground service", e)
        false
    }

    private fun buildNotification(blockedCount: Int): Notification {
        val turnOff = PendingIntent.getService(
            this,
            0,
            Intent(this, BlockerVpnService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(resources.getQuantityString(R.plurals.notification_title, blockedCount, blockedCount))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notification_turn_off), turnOff)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "BlockerVpnService"
        private const val ACTION_APPLY = "io.github.teykaijun.netblocker.action.APPLY"
        private const val ACTION_TURN_OFF = "io.github.teykaijun.netblocker.action.TURN_OFF"
        private const val CHANNEL_ID = "blocking_status"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500
        private const val RESTART_BACKOFF_MS = 5_000L

        // Private addresses that exist only inside the on-device tunnel.
        private const val TUNNEL_ADDRESS_V4 = "10.111.222.1"
        private const val TUNNEL_DNS_V4 = "10.111.222.2"
        private const val TUNNEL_ADDRESS_V6 = "fdb4:1c3e:9a27::1"
        private const val TUNNEL_DNS_V6 = "fdb4:1c3e:9a27::2"

        private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
        val state: StateFlow<TunnelState> = _state.asStateFlow()

        /**
         * Makes the service match the saved settings: starts or rebuilds the tunnel when blocking is on
         * and at least one app is selected, and stops it otherwise.
         *
         * Starting requires the VPN permission; callers obtain it with [VpnService.prepare] first.
         */
        fun sync(context: Context) {
            val settings = BlockerSettings.get(context)
            val intent = Intent(context, BlockerVpnService::class.java).setAction(ACTION_APPLY)
            if (settings.blockingEnabled.value && settings.blockedPackages.value.isNotEmpty()) {
                if (VpnService.prepare(context) == null) {
                    ContextCompat.startForegroundService(context, intent)
                }
            } else if (state.value is TunnelState.Running) {
                context.startService(intent)
            }
        }
    }
}
