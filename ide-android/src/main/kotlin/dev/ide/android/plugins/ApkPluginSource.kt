package dev.ide.android.plugins

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import dalvik.system.PathClassLoader
import dev.ide.core.plugins.PluginManifestToml
import dev.ide.platform.log.Log
import dev.ide.plugin.PluginManifest
import dev.ide.plugin.external.DiscoveredPlugin
import dev.ide.plugin.external.PluginOrigin
import dev.ide.plugin.external.PluginSource
import java.io.File
import java.security.MessageDigest

/**
 * Discovers plugins the user installed as separate apps. A plugin app declares an activity carrying
 * [PLUGIN_ACTION] and points a `meta-data` entry at the raw resource holding its manifest:
 *
 * ```xml
 * <activity android:name=".PluginInfoActivity" android:exported="true">
 *     <intent-filter>
 *         <action android:name="dev.ide.codeassist.action.PLUGIN" />
 *         <category android:name="android.intent.category.DEFAULT" />
 *     </intent-filter>
 *     <meta-data android:name="dev.ide.codeassist.plugin.manifest"
 *                android:resource="@raw/codeassist_plugin" />
 * </activity>
 * ```
 *
 * The activity is the discovery marker and doubles as the plugin app's own screen, so it is a real app rather
 * than a bare code container. The IDE reads the manifest through the package manager, with no code loaded;
 * only a plugin the user has left enabled ever reaches [DiscoveredPlugin.classLoader].
 *
 * Code comes off the installed APK exactly as the system installed it. Nothing is downloaded, written, or
 * dexed here: [PathClassLoader] reads the package's own `base.apk` (plus its splits) from the read-only
 * install directory, already optimised by the platform. The parent is the IDE's own classloader, so a
 * plugin's references to the plugin SPI, the Kotlin stdlib, and the Compose runtime bind to the IDE's copies
 * and cannot be shadowed by a second version inside the plugin APK.
 */
class ApkPluginSource(
    context: Context,
    /**
     * Hex SHA-256 signing certificates whose plugins count as trusted. Empty leaves every plugin untrusted,
     * which is the default posture; a capability model reads this, nothing else does yet.
     */
    private val trustedSignatures: Set<String> = emptySet(),
    /**
     * Installer package names a plugin must have come from ("com.android.vending" for Google Play). Empty
     * accepts any installer, including a sideload.
     */
    private val installerAllowlist: Set<String> = emptySet(),
) : PluginSource {

    private val appContext = context.applicationContext
    private val packages: PackageManager get() = appContext.packageManager

    override val id: String get() = SOURCE_ID

    override fun discover(): List<DiscoveredPlugin> {
        val matches = try {
            packages.queryIntentActivities(Intent(PLUGIN_ACTION), PackageManager.GET_META_DATA)
        } catch (t: Throwable) {
            log.warn("could not query installed plugin apps", t)
            return emptyList()
        }
        return matches.mapNotNull { read(it) }
    }

    /** Parse one match into a [DiscoveredPlugin], or null with a logged reason if it is not usable. */
    private fun read(match: ResolveInfo): DiscoveredPlugin? {
        val activity = match.activityInfo ?: return null
        val pkg = activity.packageName
        // A plugin app cannot be the IDE itself: that would load the host's own classes a second time.
        if (pkg == appContext.packageName) return null
        if (installerAllowlist.isNotEmpty() && installerOf(pkg) !in installerAllowlist) {
            log.warn("ignoring plugin app $pkg: not installed by an accepted installer")
            return null
        }

        val resourceId = activity.metaData?.getInt(META_MANIFEST, 0) ?: 0
        if (resourceId == 0) {
            log.warn("ignoring plugin app $pkg: no '$META_MANIFEST' meta-data")
            return null
        }

        val manifest = try {
            val text = packages.getResourcesForApplication(activity.applicationInfo)
                .openRawResource(resourceId)
                .use { it.readBytes().toString(Charsets.UTF_8) }
            PluginManifestToml.parse(text)
        } catch (t: Throwable) {
            log.warn("ignoring plugin app $pkg: ${t.message ?: t::class.java.name}")
            return null
        }

        val signature = signatureOf(pkg)
        return ApkPlugin(
            manifest = manifest.copy(trusted = signature != null && signature in trustedSignatures),
            origin = PluginOrigin(SOURCE_ID, pkg, signature),
            packageName = pkg,
            packages = packages,
            parent = javaClass.classLoader,
        )
    }

    /** Lowercase hex SHA-256 of the package's first signing certificate, or null if it cannot be read. */
    private fun signatureOf(pkg: String): String? = try {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packages.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            packages.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
        }
        bytes?.let { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
    } catch (t: Throwable) {
        log.warn("could not read the signing certificate of $pkg", t)
        null
    }

    private fun installerOf(pkg: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packages.getInstallSourceInfo(pkg).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            packages.getInstallerPackageName(pkg)
        }
    } catch (t: Throwable) {
        null
    }

    private class ApkPlugin(
        override val manifest: PluginManifest,
        override val origin: PluginOrigin,
        private val packageName: String,
        private val packages: PackageManager,
        private val parent: ClassLoader?,
    ) : DiscoveredPlugin {

        override fun classLoader(): ClassLoader {
            // Resolved at load rather than at discovery: an updated plugin app gets a new install path, and
            // the stale one would be gone.
            val app: ApplicationInfo = packages.getApplicationInfo(packageName, 0)
            val apks = buildList {
                add(app.sourceDir)
                app.splitSourceDirs?.let { addAll(it) }
            }
            return PathClassLoader(apks.joinToString(File.pathSeparator), app.nativeLibraryDir, parent)
        }
    }

    companion object {
        /** Id recorded on every [PluginOrigin] this source produces. */
        const val SOURCE_ID = "apk"

        /** The intent action a plugin app's marker activity declares. */
        const val PLUGIN_ACTION = "dev.ide.codeassist.action.PLUGIN"

        /** The meta-data key pointing at the raw resource holding the plugin's TOML manifest. */
        const val META_MANIFEST = "dev.ide.codeassist.plugin.manifest"

        private val log = Log.logger("ApkPluginSource")
    }
}
