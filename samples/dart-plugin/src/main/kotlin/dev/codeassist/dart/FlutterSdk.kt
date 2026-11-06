package dev.codeassist.dart

import dev.ide.platform.log.Logger
import dev.ide.plugin.PluginRegistration
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/**
 * The Flutter this plugin carries (tools/dart-sdk/fetch-flutter.sh): a FLUTTER_ROOT the framework packages
 * resolve from, the assets every app's bundle starts with, and, in the plugin's own APK, the engine and the
 * player that runs apps. One Flutter version, the one whose Dart the plugin carries: the kernel the IDE
 * compiles must match the Dart VM inside the engine.
 */
class FlutterSdk(private val reg: PluginRegistration, private val dart: DartSdk, private val log: Logger) {

    sealed interface Status {
        data class Ready(val root: Path) : Status
        data class Unavailable(val reason: String) : Status
    }

    /** `FLUTTER_ROOT`: `packages/flutter`, `bin/cache/pkg/sky_engine`, the patched platform, the version files. */
    val root: Path get() = dart.home.resolve("flutter").resolve(VERSION)
    val baseAssets: Path get() = dart.home.resolve("flutter").resolve("base-assets-$VERSION")

    /** The core libraries in kernel form, patched for Flutter, that an app compiles against. */
    val platformDir: Path get() = root.resolve("bin/cache/artifacts/engine/common/flutter_patched_sdk")

    /** Whether this build of the plugin carries Flutter at all (the stage is optional at build time). */
    val packaged: Boolean
        get() = javaClass.classLoader?.getResource(ROOT_ASSET) != null && reg.nativeLibrary("flutter") != null

    @Volatile
    private var ready: Status.Ready? = null

    /** Unpack the Flutter root and the base assets once per version. Safe across the IDE's processes. */
    @Synchronized
    fun prepare(): Status {
        ready?.let { return it }
        if (!packaged) return Status.Unavailable(NO_FLUTTER)
        return try {
            Files.createDirectories(root.parent)
            RandomAccessFile(root.parent.resolve(".lock").toFile(), "rw").use { raf ->
                raf.channel.lock().use {
                    unpack(ROOT_ASSET, root)
                    unpack(BASE_ASSETS, baseAssets)
                }
            }
            Status.Ready(root).also { ready = it }
        } catch (e: Exception) {
            log.warn("could not prepare Flutter", e)
            Status.Unavailable("Flutter could not be prepared: ${e.message}")
        }
    }

    private fun unpack(asset: String, into: Path) {
        val stamp = into.resolve(".unpacked")
        if (Files.isRegularFile(stamp)) return
        val stream = javaClass.classLoader?.getResourceAsStream(asset) ?: error("$asset is not packaged")
        val started = System.nanoTime()
        if (Files.exists(into)) into.toFile().deleteRecursively()
        Files.createDirectories(into)
        var files = 0
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = into.resolve(entry.name).normalize()
                require(target.startsWith(into)) { "entry escapes $into: ${entry.name}" }
                if (entry.isDirectory) Files.createDirectories(target) else {
                    Files.createDirectories(target.parent)
                    Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING)
                    files++
                }
                zip.closeEntry()
            }
        }
        Files.write(stamp, VERSION.toByteArray())
        log.info("unpacked $files files of $asset in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    companion object {
        /** The Flutter release staged by fetch-flutter.sh; its Dart is [DartSdk.VERSION]. */
        const val VERSION = "3.47.9"
        private const val ROOT_ASSET = "assets/flutter-root.zip"
        private const val BASE_ASSETS = "assets/flutter-base-assets.zip"
        const val NO_FLUTTER = "This build of the Dart plugin carries no Flutter (tools/dart-sdk/fetch-flutter.sh was not run)."

        /** Whether the package whose pubspec is [pubspec] is a Flutter app or package: it depends on the SDK's `flutter`. */
        fun isFlutter(pubspec: String): Boolean =
            // `flutter:` with `sdk: flutter` under it: the dependency on the framework itself (flutter_test's
            // entry is `flutter_test:`, and the `flutter:` section at the top level has no `sdk:`).
            Regex("""(?m)^[ \t]+flutter:[ \t]*\r?\n[ \t]+sdk:[ \t]*flutter\b""").containsMatchIn(pubspec)

        fun isFlutterPackage(pkg: Path): Boolean =
            runCatching { isFlutter(String(Files.readAllBytes(pkg.resolve(DartPackages.PUBSPEC)))) }.getOrDefault(false)
    }
}
