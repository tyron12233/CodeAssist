package dev.ide.platform

import java.net.URL
import java.net.URLClassLoader

/**
 * Which packages a loaded **tool** classpath (a Kotlin compiler plugin, a KSP annotation processor) must
 * resolve from its OWN jars instead of inheriting the app's copy.
 *
 * Tool classloaders are deliberately parent-first, and that is load-bearing: the SPI types the app builds and
 * hands across the boundary (`CompilerPluginRegistrar`, `SymbolProcessorProvider`, `KSPConfig`/`KSPLogger`),
 * the Kotlin stdlib and the IntelliJ platform must be the SAME classes on both sides, which is why a bundle
 * even drops those jars. But parent-first also means a library the app happens to carry for its OWN reasons
 * shadows the version the tool was compiled against, which is fatal when the two are API-incompatible:
 *
 *  - `dagger.*`: the app dexes bundletool (in-process `.aab` building), whose closure drags in Dagger ~2.2x.
 *    The bundled Hilt/Dagger processor is Dagger 2.6x, whose generated components call
 *    `DoubleCheck.provider(dagger.internal.Provider)`, a type/overload the old runtime does not have, so the
 *    processor died the moment it built its own object graph: `NoSuchMethodError: No static method
 *    provider(Ldagger/internal/Provider;)Ldagger/internal/Provider; in class Ldagger/internal/DoubleCheck;`.
 *
 *  - `com.google.common.*` (guava): the app ships 32.0.1-jre (bundletool's collections), while the processor
 *    bundles ship 30.1.1 (moshi), 33.2.1 (room) and 33.6.0 (hilt). On ART this is worse than ordinary version
 *    skew, because the app's copy is dexed at build time and a bundle's copy is dexed on device, and D8 gives
 *    each lambda a synthetic class named after its context class plus a per-compilation index
 *    (`CollectCollectors$$ExternalSyntheticLambda62`). That index is stable for neither input set nor guava
 *    version, and D8 shares one synthetic between unrelated contexts, so under parent-first a tool class holds
 *    a reference to a `com.google.common.*` synthetic name that denotes a DIFFERENT lambda in the app's dex.
 *    Dexing the hilt bundle and diffing its synthetics against the shipped APK's finds 83 names whose
 *    interface disagrees; the Hilt processor died on one of them, inside a guava collector:
 *    `IncompatibleClassChangeError: Class 'com.google.common.collect.CollectCollectors$$ExternalSyntheticLambda62'
 *    does not implement interface 'java.util.function.Supplier' in call to 'java.util.function.Supplier.get()'`
 *    (in the app's dex that name is a `Function`). Only child-first keeps a bundle's guava classes and the
 *    synthetics they reference on the same side of the boundary.
 *
 * The rule this leaves for a tool bundle: a package it ships must either be dropped from the bundle because
 * the app provides it (the Kotlin stdlib, coroutines, the KSP SPI) or be listed here. Shipping a second copy
 * AND inheriting the app's is what produces the failures above.
 *
 * A package belongs here only when no instance of it crosses the classloader boundary. `dagger.*` is purely an
 * implementation detail of the processor's own dependency injection, and guava appears only inside the
 * processors' own collections; the app hands neither across. Child-first still falls back to the parent when
 * the tool bundle does not carry the class at all, so listing a package the bundle lacks is harmless.
 */
object ToolClassIsolation {

    /** Package prefixes a tool classpath resolves from its own jars first. */
    val CHILD_FIRST_PACKAGES: List<String> = listOf("dagger.", "com.google.common.")

    /** Whether [className] must be loaded from the tool's own classpath before delegating to the parent. */
    fun isChildFirst(className: String): Boolean = CHILD_FIRST_PACKAGES.any { className.startsWith(it) }
}

/**
 * The desktop tool classloader: a parent-first [URLClassLoader] over a tool's jars that makes
 * [ToolClassIsolation.CHILD_FIRST_PACKAGES] child-first. `:ide-android` carries the ART counterpart
 * (`ArtKotlinPluginLoader`, a `DexClassLoader` with the same override), since a jar's `.class` bytes cannot be
 * defined at runtime on ART. Only the base class differs; the delegation policy is shared.
 */
class ToolUrlClassLoader(urls: Array<URL>, parent: ClassLoader?) : URLClassLoader(urls, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (ToolClassIsolation.isChildFirst(name)) {
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                // Absent from the tool's own jars: fall through to the normal parent-first delegation.
                try {
                    return findClass(name).also { if (resolve) resolveClass(it) }
                } catch (_: ClassNotFoundException) {
                }
            }
        }
        return super.loadClass(name, resolve)
    }
}
