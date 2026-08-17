package dev.ide.android

import dalvik.system.DexClassLoader
import dev.ide.android.support.tools.ArtReflectionRewrite
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.android.support.tools.DexInputDedup
import dev.ide.lang.kotlin.compile.KotlinPluginLoader
import dev.ide.platform.ToolClassIsolation
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.stream.Collectors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The ART [KotlinPluginLoader]: dexes a compiler plugin's jars with D8 in-process, then loads the dex
 * through a [DexClassLoader] so the plugin's `CompilerPluginRegistrar` can be instantiated on device. A
 * jar's `.class` bytes can't be defined at runtime on ART (only dex can), which is why the desktop's
 * `DefaultKotlinPluginLoader` (a plain `URLClassLoader` over the jars) doesn't translate; the registration
 * code in `KotlinJvmCompiler` is otherwise identical across the two.
 *
 * Content-addressed: the dexed `plugin.jar` is cached under [cacheRoot]/<hash> keyed by the classpath's
 * path+size+mtime, so a plugin is dexed once per version (the D8 pass is the expensive part). The parent is
 * the app classloader, which holds the dexed Kotlin compiler and any plugin registrar already dexed into the
 * app (e.g. Compose), so a registrar referencing those resolves through parent delegation, except for the
 * packages [ToolClassIsolation] pins to the tool's own jars (see [ToolDexClassLoader]).
 */
class ArtKotlinPluginLoader(
    private val androidJar: Path,
    private val cacheRoot: Path,
    private val minApi: Int,
) : KotlinPluginLoader {

    override fun load(classpath: List<Path>): ClassLoader {
        val jars = classpath.filter { Files.isRegularFile(it) }
        val cacheDir = cacheRoot.resolve(hash(jars))
        val pluginJar = cacheDir.resolve("plugin.jar")
        if (!Files.isRegularFile(pluginJar)) {
            Files.createDirectories(cacheDir)
            // ART lacks a few JDK-9 reflection methods (AccessibleObject.trySetAccessible) that processor bytecode
            // calls — androidx.room's XProcessing hits it and crashes Room's KSP processor on device. Patch them
            // out before dexing so the DexClassLoader'd code doesn't NoSuchMethodError. The rewrite is deterministic
            // in `jars`, so the dex the `hash(jars)` cache stores stays valid; the patched jars are throwaway
            // (only D8 reads them), so they go to a UNIQUE temp dir — two concurrent loads of the same classpath
            // (parallel module compiles) must not write the same patched-jar path, the race packageDex also guards.
            val artSafe = Files.createTempDirectory(cacheDir, "art-safe-")
            try {
                val patched = ArtReflectionRewrite.patch(jars, artSafe)
                // Dex has no shadowing rule, so a classpath a `URLClassLoader` loads fine (first jar wins) is
                // simply undexable: D8 fails the whole input on `Duplicate class`. A module that activates two
                // bundled KSP processors hits this immediately: every processor closure ships its own copy of
                // the shared transitive libraries. Apply the classloader's own first-wins precedence instead.
                val program = DexInputDedup.firstWins(patched, artSafe.resolve("dedup"))
                val dexDir = cacheDir.resolve("dex")
                Files.createDirectories(dexDir)
                val r = D8InProcessDexer().dex(program, androidJar, minApi, release = false, outDir = dexDir, threads = 0, desugaredLibConfig = null)
                check(r.success) { "failed to dex compiler-plugin classpath: ${r.log.joinToString("\n")}" }
                packageDex(dexDir, pluginJar)
            } finally {
                runCatching { artSafe.toFile().deleteRecursively() }
            }
        }
        // optimizedDirectory is deprecated but must NOT be null: on API 26 `DexClassLoader.<init>` still does
        // `new File(optimizedDirectory)`, which NPEs on null (surfaced by KspArtSpikeTest — the first thing to
        // actually exercise this path on device). Pass an app-private odex dir; it is ignored on newer APIs.
        val odex = cacheDir.resolve("odex")
        Files.createDirectories(odex)
        return ToolDexClassLoader(pluginJar.toString(), odex.toString(), javaClass.classLoader)
    }

    /**
     * Zip D8's `classes*.dex` output into one jar a [DexClassLoader] can read, written READ-ONLY.
     *
     * The read-only part is load-bearing on Android 14+ (API 34): the runtime refuses to load a WRITABLE
     * dex/jar through a class loader (`SecurityException: Writable dex file '…' is not allowed`), the same W^X
     * rule [R8ForkSupport] handles for the forked-VM classpath. Written to a temp sibling then atomically moved
     * into place so a crash mid-write never leaves a half-written (and now read-only, hence un-rewritable)
     * `plugin.jar` the content-addressed cache's bare existence check would then reuse forever.
     */
    private fun packageDex(dexDir: Path, pluginJar: Path) {
        val dexes = Files.list(dexDir).use { s ->
            s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList())
        }
        check(dexes.isNotEmpty()) { "D8 produced no dex for the compiler-plugin classpath" }
        // A UNIQUE temp per attempt: two `load()`s for the same plugin classpath (parallel module compiles) can
        // both miss the cache and package concurrently — a shared temp path would let them corrupt each other's
        // write. Whichever wins the atomic move publishes byte-identical dex, so the loser is harmless.
        val tmp = Files.createTempFile(pluginJar.parent, "plugin", ".jar.tmp")
        try {
            ZipOutputStream(Files.newOutputStream(tmp)).use { zip ->
                for (dex in dexes) {
                    zip.putNextEntry(ZipEntry(dex.fileName.toString()))
                    Files.copy(dex, zip)
                    zip.closeEntry()
                }
            }
            tmp.toFile().setReadOnly() // Android 14+ won't load a writable dex/jar through a class loader (W^X)
            Files.move(tmp, pluginJar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            runCatching { tmp.toFile().setWritable(true); Files.deleteIfExists(tmp) }
        }
    }

    /**
     * The ART counterpart of `ToolUrlClassLoader`: parent-first (so the SPI/compiler/stdlib types stay the
     * app's), except for [ToolClassIsolation.CHILD_FIRST_PACKAGES], which the tool's own dex must supply.
     * Without this, the app's bundletool-provided Dagger ~2.2x shadowed the bundled Hilt processor's Dagger
     * 2.6x and the processor died with `NoSuchMethodError` on `DoubleCheck.provider(dagger.internal.Provider)`.
     */
    private class ToolDexClassLoader(dexPath: String, odex: String, parent: ClassLoader?) :
        DexClassLoader(dexPath, odex, null, parent) {
        // No explicit class-loading lock: libcore's `ClassLoader.loadClass` isn't synchronized either (ART's
        // class linker is what serializes definition, and returns the already-defined class), and Android's
        // ClassLoader doesn't expose `getClassLoadingLock`.
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (ToolClassIsolation.isChildFirst(name)) {
                findLoadedClass(name)?.let { return it }
                // Absent from the tool's dex: fall through to the normal parent-first delegation.
                try {
                    return findClass(name)
                } catch (_: ClassNotFoundException) {
                }
            }
            return super.loadClass(name, resolve)
        }
    }

    private fun hash(jars: List<Path>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (j in jars.sortedBy { it.toString() }) {
            md.update(j.toString().toByteArray(Charsets.UTF_8))
            runCatching {
                val a = Files.readAttributes(j, BasicFileAttributes::class.java)
                md.update(a.size().toString().toByteArray(Charsets.UTF_8))
                md.update(a.lastModifiedTime().toMillis().toString().toByteArray(Charsets.UTF_8))
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }.substring(0, 32)
    }
}
