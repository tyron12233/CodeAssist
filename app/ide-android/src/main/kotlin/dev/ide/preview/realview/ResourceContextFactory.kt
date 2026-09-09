package dev.ide.preview.realview

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.LayoutInflater
import java.io.File
import kotlin.math.roundToInt

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
        /** When set, installed as the returned context's `LayoutInflater` factory (via
         *  `getSystemService(LAYOUT_INFLATER_SERVICE)`), so the framework inflater instantiates non-framework
         *  tags through it — the interpret path's VM view factory. Null keeps the default class-loading inflater. */
        inflaterFactory: LayoutInflater.Factory2? = null,
    ): PreviewContext {
        val config = Configuration(appContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                (if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            // The REQUESTED device-frame density, not the host device's — dp→px, `@dimen` and `-*dpi`
            // qualifiers must resolve at the previewed frame's scale. Both strategies honor it: the
            // configuration-context path via createConfigurationContext, the AssetManager path via the
            // Resources constructor's updateConfiguration (which rebuilds DisplayMetrics from densityDpi).
            densityDpi = (density * DisplayMetrics.DENSITY_DEFAULT).roundToInt()
        }
        // Build an ISOLATED Resources/AssetManager over the project's `resources.ap_` so the project arsc is its
        // OWN package at id 0x7f and `getIdentifier(name, type, projectPackage)` resolves. The public
        // `ResourcesLoader` path (`addLoaders`) augments the IDE APP's own Resources, where 0x7f already belongs
        // to the host (`com.tyron.code`); the project package then collides with the host's and getIdentifier
        // returns 0 (every project layout reads as "not found"). So the isolated `addAssetPath` path is primary;
        // `ResourcesLoader` is kept only as a fallback for when the hidden-API path is unavailable (API ≥ 30).
        return runCatching {
            viaAddAssetPath(appContext, resourcesAp, classLoader, packageName, themeName, config, inflaterFactory)
        }.getOrElse { e ->
            if (Build.VERSION.SDK_INT >= 30) viaResourcesLoader(appContext, resourcesAp, classLoader, packageName, themeName, config, inflaterFactory)
            else throw e
        }
    }

    /** API 30+: public `ResourcesLoader`. Augments a configuration-context's resources with the linked apk, and
     *  removes the loader on close so the host app's resources are left untouched. */
    private fun viaResourcesLoader(
        appContext: Context, resourcesAp: File, classLoader: ClassLoader,
        packageName: String, themeName: String?, config: Configuration,
        inflaterFactory: LayoutInflater.Factory2?,
    ): PreviewContext {
        val loader = android.content.res.loader.ResourcesLoader()
        val pfd = ParcelFileDescriptor.open(resourcesAp, ParcelFileDescriptor.MODE_READ_ONLY)
        val provider = android.content.res.loader.ResourcesProvider.loadFromApk(pfd)
        loader.addProvider(provider)
        val base = appContext.createConfigurationContext(config)
        base.resources.addLoaders(loader)
        val ctx = wrap(base, base.resources, classLoader, packageName, themeName, inflaterFactory)
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
        inflaterFactory: LayoutInflater.Factory2?,
    ): PreviewContext {
        HiddenApi.ensureExemptions()
        val assets = AssetManager::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(assets, resourcesAp.absolutePath)
        @Suppress("DEPRECATION")
        val resources = Resources(assets, DisplayMetrics().apply { setTo(appContext.resources.displayMetrics) }, config)
        val ctx = wrap(appContext, resources, classLoader, packageName, themeName, inflaterFactory)
        return PreviewContext(ctx) { runCatching { (assets as? AutoCloseable)?.close() } }
    }

    /** A context whose resources/assets/classLoader are ours and whose theme is the project's activity theme. If
     *  [inflaterFactory] is set, `LAYOUT_INFLATER_SERVICE` yields a cloned inflater carrying it, so the
     *  framework instantiates non-framework tags through the factory rather than by class loading. */
    private fun wrap(
        base: Context, resources: Resources, cl: ClassLoader, pkg: String, themeName: String?,
        inflaterFactory: LayoutInflater.Factory2?,
    ): Context = object : ContextWrapper(base) {
        private val themeRef: Resources.Theme by lazy {
            resources.newTheme().apply {
                val id = PreviewThemes.resolve(resources, pkg, themeName)
                if (id != 0) applyStyle(id, true)
            }
        }
        // A single inflater instance per context (LayoutInflater.setFactory2 throws if called twice), cloned
        // into this wrapper so inflated views get our Resources, with the VM factory installed. cloneInContext
        // copies the base inflater's factory (if any), and setFactory2 then throws — so build it defensively;
        // a null result falls back to the default inflater (interpreted views won't be created, surfaced by the
        // render's error path).
        private val inflaterRef: LayoutInflater? by lazy {
            inflaterFactory?.let { f ->
                runCatching {
                    LayoutInflater.from(base).cloneInContext(this).apply { factory2 = f }
                }.onFailure {
                    dev.ide.platform.log.Log.logger("ide.preview")
                        .warn("preview inflater factory not installed: ${it.javaClass.simpleName}: ${it.message}")
                }.getOrNull()
            }
        }
        override fun getResources(): Resources = resources
        override fun getAssets(): AssetManager = resources.assets
        override fun getClassLoader(): ClassLoader = cl
        override fun getTheme(): Resources.Theme = themeRef
        override fun getSystemService(name: String): Any? =
            if (name == LAYOUT_INFLATER_SERVICE) inflaterRef ?: super.getSystemService(name)
            else super.getSystemService(name)
    }
}

/**
 * Resolves the style id the preview renders under. The manifest theme is preferred — a project style, or an
 * `android:`-prefixed framework style (from `@android:style/…`). When it can't be resolved (no manifest theme,
 * a placeholder, or a name the linked arsc doesn't carry), fall back to a Material/AppCompat day-night theme
 * the project's merged resources actually contain — inflating a Material/AppCompat widget under a bare theme
 * throws its theme-enforcement error and the whole render drops to owned rendering — then to the framework's
 * DeviceDefault as the last resort.
 */
internal object PreviewThemes {

    /** Ordered by generation; presence in the merged arsc implies the matching library is a dependency. */
    private val FALLBACKS = listOf(
        "Theme.Material3.DayNight.NoActionBar",
        "Theme.MaterialComponents.DayNight.NoActionBar",
        "Theme.AppCompat.DayNight.NoActionBar",
    )

    fun resolve(resources: Resources, pkg: String, themeName: String?): Int {
        themeName?.substringAfterLast('/')?.let { raw ->
            val (themePkg, name) =
                if (raw.startsWith("android:")) "android" to raw.removePrefix("android:") else pkg to raw
            resources.getIdentifier(name, "style", themePkg).takeIf { it != 0 }?.let { return it }
        }
        for (name in FALLBACKS) {
            resources.getIdentifier(name, "style", pkg).takeIf { it != 0 }?.let { return it }
        }
        return resources.getIdentifier("Theme.DeviceDefault.DayNight", "style", "android")
            .takeIf { it != 0 }
            ?: resources.getIdentifier("Theme.DeviceDefault", "style", "android")
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
