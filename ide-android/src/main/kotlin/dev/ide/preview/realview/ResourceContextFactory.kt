package dev.ide.preview.realview

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import java.io.File

/**
 * Builds a real [Context]/[Resources] over the build's aapt2-linked `resources.ap_`, so the framework's real
 * `LayoutInflater` + real views resolve `@`/`?attr`/styleables against the project's actual resource table.
 *
 * Two strategies (per the plan): on API ≥ 30 the public `ResourcesLoader` augments a configuration context's
 * resources with the linked apk (no hidden APIs); below 30 it falls back to a fresh `AssetManager` +
 * reflective `addAssetPath` (greylisted — guarded by [HiddenApi]). The returned [PreviewContext] must be
 * [close]d after rendering to release the loader / file descriptor.
 */
internal object ResourceContextFactory {

    class PreviewContext(val context: Context, private val cleanup: () -> Unit) : AutoCloseable {
        override fun close() {
            runCatching { cleanup() }
        }
    }

    fun create(
        appContext: Context,
        resourcesAp: File,
        classLoader: ClassLoader,
        packageName: String,
        themeName: String?,
        density: Float,
        night: Boolean,
    ): PreviewContext {
        val config = Configuration(appContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }
        // Build an ISOLATED Resources/AssetManager over the project's `resources.ap_` so the project arsc is its
        // OWN package at id 0x7f and `getIdentifier(name, type, projectPackage)` resolves. The public
        // `ResourcesLoader` path (`addLoaders`) augments the IDE APP's own Resources, where 0x7f already belongs
        // to the host (`com.tyron.code`); the project package then collides with the host's and getIdentifier
        // returns 0 (every project layout reads as "not found"). So the isolated `addAssetPath` path is primary;
        // `ResourcesLoader` is kept only as a fallback for when the hidden-API path is unavailable (API ≥ 30).
        return runCatching {
            viaAddAssetPath(appContext, resourcesAp, classLoader, packageName, themeName, config)
        }.getOrElse { e ->
            if (Build.VERSION.SDK_INT >= 30) viaResourcesLoader(appContext, resourcesAp, classLoader, packageName, themeName, config)
            else throw e
        }
    }

    /** API 30+: public `ResourcesLoader`. Augments a configuration-context's resources with the linked apk, and
     *  removes the loader on close so the host app's resources are left untouched. */
    private fun viaResourcesLoader(
        appContext: Context, resourcesAp: File, classLoader: ClassLoader,
        packageName: String, themeName: String?, config: Configuration,
    ): PreviewContext {
        val loader = android.content.res.loader.ResourcesLoader()
        val pfd = ParcelFileDescriptor.open(resourcesAp, ParcelFileDescriptor.MODE_READ_ONLY)
        val provider = android.content.res.loader.ResourcesProvider.loadFromApk(pfd)
        loader.addProvider(provider)
        val base = appContext.createConfigurationContext(config)
        base.resources.addLoaders(loader)
        val ctx = wrap(base, base.resources, classLoader, packageName, themeName)
        return PreviewContext(ctx) {
            runCatching { base.resources.removeLoaders(loader) }
            runCatching { provider.close() }
            runCatching { pfd.close() }
        }
    }

    /** Pre-30 fallback: a fresh [AssetManager] (auto-includes the framework) + reflective `addAssetPath`. */
    private fun viaAddAssetPath(
        appContext: Context, resourcesAp: File, classLoader: ClassLoader,
        packageName: String, themeName: String?, config: Configuration,
    ): PreviewContext {
        HiddenApi.ensureExemptions()
        val assets = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, resourcesAp.absolutePath)
        @Suppress("DEPRECATION")
        val resources = Resources(assets, DisplayMetrics().apply { setTo(appContext.resources.displayMetrics) }, config)
        val ctx = wrap(appContext, resources, classLoader, packageName, themeName)
        return PreviewContext(ctx) { runCatching { (assets as? AutoCloseable)?.close() } }
    }

    /** A context whose resources/assets/classLoader are ours and whose theme is the project's activity theme. */
    private fun wrap(
        base: Context, resources: Resources, cl: ClassLoader, pkg: String, themeName: String?,
    ): Context = object : ContextWrapper(base) {
        private val themeRef: Resources.Theme by lazy {
            resources.newTheme().apply {
                val id = themeName?.substringAfterLast('/')?.let { resources.getIdentifier(it, "style", pkg) } ?: 0
                if (id != 0) applyStyle(id, true)
            }
        }
        override fun getResources(): Resources = resources
        override fun getAssets(): AssetManager = resources.assets
        override fun getClassLoader(): ClassLoader = cl
        override fun getTheme(): Resources.Theme = themeRef
    }
}

/**
 * Best-effort hidden-API unsealing for the pre-30 `addAssetPath` fallback. On the API 30+ device the preview
 * uses `ResourcesLoader` (public) and this never runs. Kept minimal and best-effort; a robust meta-reflection
 * bypass can replace it if the legacy path proves necessary on real pre-30 devices.
 */
internal object HiddenApi {
    @Volatile private var attempted = false

    @Synchronized
    fun ensureExemptions() {
        if (attempted) return
        attempted = true
        if (Build.VERSION.SDK_INT < 28) return
        runCatching {
            val vmRuntime = Class.forName("dalvik.system.VMRuntime")
            val getRuntime = vmRuntime.getDeclaredMethod("getRuntime").apply { isAccessible = true }
            val setExemptions = vmRuntime
                .getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .apply { isAccessible = true }
            setExemptions.invoke(getRuntime.invoke(null), arrayOf("L"))
        }
    }
}
