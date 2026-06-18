package dev.ide.android

import dalvik.system.DexClassLoader
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.core.ComposePreviewLibs
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Builds the [ClassLoader] the on-device Compose interpreter dispatches library calls through, so the user's
 * project libraries (`Text`/Material3, third-party Compose widgets, sibling library modules) are actually
 * callable on device — where, unlike desktop, the project's libraries aren't loaded in the IDE process.
 *
 * It D8-dexes the project's resolved library jars once (cached on disk by the dependency [fingerprint] and in
 * memory by the same key) and wraps the dex in a [DexClassLoader] whose **parent is the IDE app loader**.
 * Parent-first delegation means `androidx.compose.runtime.*` and `android.*` resolve to the IDE's *bundled*
 * runtime, so the `Composer` threaded in from the IDE's composition is type-compatible — while a library the
 * IDE doesn't bundle loads from the child dex. (Approach A, `docs/compose-interpreter.md`: the project's
 * Compose-runtime version is required to be ABI-compatible with the IDE's bundled runtime.)
 *
 * Returns null when there's nothing to load (no `android.jar`, no jars, or dexing fails) — the renderer then
 * falls back to the IDE's bundled Compose, which still serves standard composables.
 */
object ComposeLibraryLoader {

    private val cache = ConcurrentHashMap<String, ClassLoader>()

    fun loaderFor(libs: ComposePreviewLibs): ClassLoader? {
        if (libs.androidJar == null || libs.jars.isEmpty()) return null
        cache[libs.fingerprint]?.let { return it }
        return synchronized(this) {
            cache[libs.fingerprint] ?: build(libs)?.also { cache[libs.fingerprint] = it }
        }
    }

    private fun build(libs: ComposePreviewLibs): ClassLoader? {
        val base = libs.cacheDir.resolve(libs.fingerprint).toFile()
        val dexDir = File(base, "dex")
        val oatDir = File(base, "oat").apply { mkdirs() }
        val marker = File(base, ".ok") // content-keyed → a present marker means this dex is up to date

        if (!marker.exists()) {
            dexDir.deleteRecursively(); dexDir.mkdirs()
            val result = runCatching {
                D8InProcessDexer().dex(libs.jars, libs.androidJar!!, libs.minApi, false, dexDir.toPath())
            }.getOrElse { return null }
            if (!result.success) return null
            runCatching { marker.writeText(libs.fingerprint) }
        }

        val dexes = dexDir.walkTopDown().filter { it.extension == "dex" }.map { it.absolutePath }.toList()
        if (dexes.isEmpty()) return null
        return DexClassLoader(dexes.joinToString(File.pathSeparator), oatDir.absolutePath, null, javaClass.classLoader)
    }
}
