package dev.ide.android.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dev.ide.core.plugins.PluginPackageFacts
import dev.ide.core.plugins.PluginPackages
import java.io.File
import java.security.MessageDigest

/** [PluginPackages] from the package manager. */
class AndroidPluginPackages(private val context: Context) : PluginPackages {
    private val packages: PackageManager get() = context.packageManager

    /** This IDE's own signing certificate, read once: what "signed like CodeAssist" is compared with. */
    private val ownSignature: String? by lazy { signatureOf(context.packageName) }

    override fun facts(packageName: String): PluginPackageFacts? = runCatching {
        val info = packageInfo(packageName)
        val apk = info.applicationInfo?.sourceDir?.let(::File)
        PluginPackageFacts(
            appVersion = info.versionName,
            firstInstalledAt = info.firstInstallTime,
            lastUpdatedAt = info.lastUpdateTime,
            sizeBytes = apk?.length() ?: 0L,
            signedLikeIde = ownSignature != null && signatureOf(packageName) == ownSignature,
            installer = installerOf(packageName),
        )
    }.getOrNull()

    override fun openAppInfo(packageName: String): Boolean = start(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )

    override fun openApp(packageName: String): Boolean {
        val launch = packages.getLaunchIntentForPackage(packageName) ?: return false
        return start(launch)
    }

    private fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun packageInfo(pkg: String, flags: Int = 0): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) packages.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else @Suppress("DEPRECATION") packages.getPackageInfo(pkg, flags)

    private fun installerOf(pkg: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) packages.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") packages.getInstallerPackageName(pkg)
    }.getOrNull()

    private fun signatureOf(pkg: String): String? = runCatching {
        val bytes = if (Build.VERSION.SDK_INT >= 28) {
            packageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }.getOrNull()
}
