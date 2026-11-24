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

    /**
     * The debug engine this plugin carries for the device's ABI (`libflutter.so`), which an app built here
     * packages as its own, or null when the plugin carries none.
     */
    val engine: Path? get() = reg.nativeLibrary("flutter")

    /**
     * One of the Dart VM's snapshots that every debug app's `flutter_assets` holds beside its program
     * (`vm_snapshot_data`, `isolate_snapshot_data`). The player's bundle has them, made by this same engine.
     */
    fun vmSnapshot(name: String): java.io.InputStream? =
        javaClass.classLoader?.getResourceAsStream("assets/flutter_assets/$name")

    /** The release engine (`libflutter_release.so`), which a release build packages as the app's `libflutter.so`. */
    val releaseEngine: Path? get() = reg.nativeLibrary("flutter_release")

    /**
     * Dart's gen_snapshot built for this device (`libgen_snapshot.so`): a release build's kernel to machine code.
     * Built like the release engine's own VM (product mode, compressed pointers on 64-bit), or the engine refuses
     * the snapshot (tools/dart-sdk/build-gen-snapshot.sh).
     */
    val genSnapshot: Path? get() = reg.nativeLibrary("gen_snapshot")

    /** The core libraries in kernel form for product mode, which a release build's kernel compiles against. */
    val productPlatformDir: Path
        get() = dart.home.resolve("flutter").resolve("product-$VERSION").resolve("flutter_patched_sdk_product")

    @Volatile
    private var ready: Status.Ready? = null

    @Volatile
    private var releaseReady: Status.Ready? = null

    /** Unpack the Flutter root and the base assets once per version. Safe across the IDE's processes. */
    @Synchronized
    fun prepare(): Status {
        ready?.let { return it }
        if (!packaged) return Status.Unavailable(NO_FLUTTER)
        return try {
            locked {
                unpack(ROOT_ASSET, root)
                unpack(BASE_ASSETS, baseAssets)
            }
            Status.Ready(root).also { ready = it }
        } catch (e: Exception) {
            log.warn("could not prepare Flutter", e)
            Status.Unavailable("Flutter could not be prepared: ${e.message}")
        }
    }

    /**
     * [prepare], plus what a release build needs besides: the release engine, gen_snapshot and the product
     * platform, which a build of the plugin made without fetch-flutter-release.sh does not carry.
     */
    @Synchronized
    fun prepareRelease(): Status {
        releaseReady?.let { return it }
        (prepare() as? Status.Unavailable)?.let { return it }
        if (releaseEngine == null || genSnapshot == null || javaClass.classLoader?.getResource(PRODUCT_ASSET) == null) {
            return Status.Unavailable(NO_RELEASE)
        }
        return try {
            locked { unpack(PRODUCT_ASSET, productPlatformDir.parent) }
            Status.Ready(root).also { releaseReady = it }
        } catch (e: Exception) {
            log.warn("could not prepare Flutter release builds", e)
            Status.Unavailable("Flutter release builds could not be prepared: ${e.message}")
        }
    }

    /** Run [block] holding the file lock the IDE's processes share, so two never unpack into one directory. */
    private fun locked(block: () -> Unit) {
        Files.createDirectories(root.parent)
        RandomAccessFile(root.parent.resolve(".lock").toFile(), "rw").use { raf ->
            raf.channel.lock().use { block() }
        }
    }

    private fun unpack(asset: String, into: Path) {
        val stamp = into.resolve(".unpacked")
        // The version alone is not enough: a plugin update can change what a version's archive holds (the SDK
        // packages added to the root), and an install that unpacked the old one must unpack again.
        val expected = "$VERSION/$LAYOUT"
        if (Files.isRegularFile(stamp) && String(Files.readAllBytes(stamp)) == expected) return
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
        Files.write(stamp, expected.toByteArray())
        log.info("unpacked $files files of $asset in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    companion object {
        /** The Flutter release staged by fetch-flutter.sh; its Dart is [DartSdk.VERSION]. */
        const val VERSION = "3.47.9"

        /**
         * What the staged archives hold, beyond the version: bump it when fetch-flutter.sh stages more, so an
         * install that unpacked an older plugin's archives unpacks them again. 2: flutter_web_plugins and
         * flutter_localizations in the root.
         */
        private const val LAYOUT = 2

        /**
         * The engine revision of [VERSION], which names the engine's Java embedding on Flutter's Maven repository
         * ([MAVEN_REPOSITORY], `io.flutter:flutter_embedding_debug:1.0.0-<engine>`). fetch-flutter.sh writes the
         * same value into its ENGINE file; the plugin's own build resolves its embedding by it.
         */
        const val ENGINE = "89c39edfdfae2949f6806e4a315c0c08522238b7"

        /** Where Flutter publishes its engine's Maven artifacts. Neither Maven Central nor Google has them. */
        const val MAVEN_REPOSITORY = "https://storage.googleapis.com/download.flutter.io"

        /** The Java half of the debug engine, which an app's `MainActivity` extends `FlutterActivity` from. */
        const val EMBEDDING_DEBUG = "io.flutter:flutter_embedding_debug:1.0.0-$ENGINE"

        /** The Java half of the release engine: the same classes, loading the app's `libapp.so` instead of a kernel. */
        const val EMBEDDING_RELEASE = "io.flutter:flutter_embedding_release:1.0.0-$ENGINE"
        private const val ROOT_ASSET = "assets/flutter-root.zip"
        private const val BASE_ASSETS = "assets/flutter-base-assets.zip"
        private const val PRODUCT_ASSET = "assets/flutter-product.zip"
        const val NO_RELEASE = "This build of the Dart plugin cannot build Flutter apps in release mode " +
            "(tools/dart-sdk/fetch-flutter-release.sh was not run). Build the debug variant."
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
