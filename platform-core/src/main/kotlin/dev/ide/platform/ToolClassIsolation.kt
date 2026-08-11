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
 * A package belongs here only when no instance of it crosses the classloader boundary. `dagger.*` is purely an
 * implementation detail of the processor's own dependency injection; the app never touches it. Child-first
 * still falls back to the parent when the tool bundle does not carry the class at all, so listing a package
 * the bundle lacks is harmless.
 *
 * Deliberately NOT listed: `com.google.common.*` (guava). The app ships 32.0.1-jre while the processor bundles
 * ship 33.x, but Room has run on device against the app's copy across releases, so flipping it would change
 * working behavior with no evidence. A guava `NoSuchMethodError` out of a processor is the signal to add it.
 */
object ToolClassIsolation {

    /** Package prefixes a tool classpath resolves from its own jars first. */
    val CHILD_FIRST_PACKAGES: List<String> = listOf("dagger.")

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
