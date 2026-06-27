package dev.ide.android

import dalvik.system.DexClassLoader
import dev.ide.android.support.tools.D8InProcessDexer
import dev.ide.lang.kotlin.compile.KotlinPluginLoader
import java.nio.file.Files
import java.nio.file.Path
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
 * app (e.g. Compose), so a registrar referencing those resolves through parent delegation.
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
            val dexDir = cacheDir.resolve("dex")
            Files.createDirectories(dexDir)
            val r = D8InProcessDexer().dex(jars, androidJar, minApi, release = false, outDir = dexDir, threads = 0, desugaredLibConfig = null)
            check(r.success) { "failed to dex compiler-plugin classpath: ${r.log.joinToString("\n")}" }
            packageDex(dexDir, pluginJar)
        }
        // optimizedDirectory is deprecated and ignored since API 26 (this app's minSdk), so null is fine.
        return DexClassLoader(pluginJar.toString(), null, null, javaClass.classLoader)
    }

    /** Zip D8's `classes*.dex` output into one jar a [DexClassLoader] can read. */
    private fun packageDex(dexDir: Path, pluginJar: Path) {
        val dexes = Files.list(dexDir).use { s ->
            s.filter { it.toString().endsWith(".dex") }.sorted().collect(Collectors.toList())
        }
        check(dexes.isNotEmpty()) { "D8 produced no dex for the compiler-plugin classpath" }
        ZipOutputStream(Files.newOutputStream(pluginJar)).use { zip ->
            for (dex in dexes) {
                zip.putNextEntry(ZipEntry(dex.fileName.toString()))
                Files.copy(dex, zip)
                zip.closeEntry()
            }
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
