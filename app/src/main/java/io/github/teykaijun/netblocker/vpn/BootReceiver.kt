package io.github.teykaijun.netblocker.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restores blocking after the phone restarts or this app is updated. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        try {
            BlockerVpnService.sync(context)
        } catch (e: RuntimeException) {
            // For example if the system refuses a background start; blocking resumes when the app is opened.
            Log.w("BootReceiver", "Could not restore blocking", e)
        }
    }
}
