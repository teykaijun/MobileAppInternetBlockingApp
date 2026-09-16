package io.github.teykaijun.netblocker.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/** An installed app that is allowed to use the internet. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasLauncherIcon: Boolean,
    /** Other installed packages that share this app's user ID. Android can only block them together. */
    val sharedUidPackages: Int,
)

object InstalledApps {

    /** Lists the apps that request internet access, sorted by name. Does IPC work: call it off the main thread. */
    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        // Core system user IDs (system server, phone, Bluetooth...) are left out on purpose:
        // routing them into the tunnel could break the device itself.
        val packages = pm.installedPackagesWithPermissions()
            .filter { (it.applicationInfo?.uid ?: 0) >= Process.FIRST_APPLICATION_UID }
        val packagesPerUid = packages.groupingBy { it.applicationInfo!!.uid }.eachCount()
        val launchable = pm.launchablePackages()

        return packages
            .filter { info ->
                info.packageName != context.packageName &&
                    info.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
            }
            .map { info ->
                val app = info.applicationInfo!!
                InstalledApp(
                    packageName = info.packageName,
                    label = app.loadLabel(pm).toString().trim().ifEmpty { info.packageName },
                    isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    hasLauncherIcon = info.packageName in launchable,
                    sharedUidPackages = packagesPerUid.getValue(app.uid) - 1,
                )
            }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun PackageManager.installedPackagesWithPermissions(): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getInstalledPackages(PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }

    private fun PackageManager.launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, 0)
        }
        return activities.mapTo(HashSet()) { it.activityInfo.packageName }
    }
}
