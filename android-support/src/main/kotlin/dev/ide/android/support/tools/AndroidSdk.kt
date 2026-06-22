package dev.ide.android.support.tools

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolved locations of the Android build toolchain. android-support never links any of these at compile
 * time; it invokes them at runtime: `aapt2`/`zipalign` are native binaries
 * shipped per-ABI in the SDK build-tools; `d8`/`apksigner` are pure-Java tools launched on a JVM. On a
 * desktop host these come from an installed Android SDK; on-device they ship inside the IDE app. The
 * launcher (`java`/`keytool`) defaults to the *current* JVM so a build needs nothing on `PATH`.
 */
class AndroidSdk(
    /** `platforms/android-<level>/android.jar` — the compile-time bootclasspath + aapt2 `-I`. */
    val androidJar: Path,
    /** `build-tools/<version>` — holds the native binaries and `lib/{d8,apksigner}.jar`. */
    val buildToolsDir: Path,
    /** JVM home used to launch the pure-Java tools; defaults to the running JVM. */
    val javaHome: Path = Paths.get(System.getProperty("java.home")),
    /**
     * The `aapt2` native binary. Defaults to the SDK build-tools location; on-device it is overridden to
     * the per-ABI prebuilt extracted into `nativeLibraryDir` (`libaapt2.so`) — see [forDevice].
     */
    val aapt2: Path = buildToolsDir.resolve(exe("aapt2")),
    /** The `zipalign` native binary; overridden on-device to `nativeLibraryDir/libzipalign.so` (see [forDevice]). */
    val zipalign: Path = buildToolsDir.resolve(exe("zipalign")),
) {
    val d8Jar: Path get() = buildToolsDir.resolve("lib").resolve("d8.jar")
    val apksignerJar: Path get() = buildToolsDir.resolve("lib").resolve("apksigner.jar")

    /**
     * `build-tools/<version>/core-lambda-stubs.jar` — the desugar bootstrap stubs (`java.lang.invoke.*`,
     * notably `StringConcatFactory` and `LambdaMetafactory`). `android.jar` omits these, so compiling or
     * analyzing Java ≥ 9 code that uses string concatenation (`"a" + b`, which the compiler lowers to an
     * `invokedynamic` against `StringConcatFactory.makeConcatWithConstants`) or lambdas against `android.jar`
     * alone reports the type "cannot be resolved … indirectly referenced from required .class files". AGP
     * puts this jar on the compile bootclasspath for exactly this reason; it is compile-only and never
     * dexed or packaged (D8 desugars the indy at dex time). Callers null-check via [Files.exists] since older
     * build-tools may omit it.
     */
    val coreLambdaStubs: Path get() = buildToolsDir.resolve("core-lambda-stubs.jar")
    val javaLauncher: Path get() = javaHome.resolve("bin").resolve(exe("java"))
    val keytool: Path get() = javaHome.resolve("bin").resolve(exe("keytool"))

    /** True when every tool the subprocess (desktop) pipeline needs is present on disk (else report, don't crash). */
    fun isComplete(): Boolean = listOf(androidJar, aapt2, d8Jar, apksignerJar, javaLauncher)
        .all { Files.exists(it) }

    /**
     * True when the on-device ([forDevice]) pipeline can run: it dexes/signs in-process (no `d8.jar`/
     * `apksigner.jar`/`java` launcher), so it needs only the bootclasspath jar and the native `aapt2`
     * binary. `zipalign` is not needed ([ApksigSigner] aligns the APK in-process via apksig).
     */
    fun hasNativeTools(): Boolean = Files.exists(androidJar) && Files.exists(aapt2)

    companion object {
        private val IS_WINDOWS = System.getProperty("os.name").orEmpty().lowercase().contains("win")

        private fun exe(name: String): String = if (IS_WINDOWS) "$name.exe" else name

        /**
         * On-device wiring: `android.jar` is the bundled SDK asset and the native tools are the per-ABI
         * prebuilts extracted into the app's `nativeLibraryDir` as `lib*.so` (the only place ART permits
         * `exec`). `buildToolsDir` points there too so `coreLambdaStubs` (compile-only, null-checked) and
         * any future native tools resolve from the same dir. The pure-Java tools (`d8`/`apksigner`) are not
         * read from disk — [AndroidBuildSystem.inProcess] supplies them in-process.
         */
        fun forDevice(androidJar: Path, nativeLibDir: Path): AndroidSdk =
            AndroidSdk(
                androidJar = androidJar,
                buildToolsDir = nativeLibDir,
                aapt2 = nativeLibDir.resolve("libaapt2.so"),
                zipalign = nativeLibDir.resolve("libzipalign.so"),
            )

        /**
         * Locate a usable toolchain under [sdkRoot]: prefer `platforms/android-<compileSdk>` (falling back
         * to the highest installed platform) and the highest `build-tools` version that ships `aapt2`.
         * Returns null when no platform or build-tools is installed.
         */
        fun detect(sdkRoot: Path, compileSdk: Int? = null): AndroidSdk? {
            val androidJar = locatePlatformJar(sdkRoot.resolve("platforms"), compileSdk) ?: return null
            val buildTools = locateBuildTools(sdkRoot.resolve("build-tools")) ?: return null
            return AndroidSdk(androidJar, buildTools)
        }

        /**
         * Locate an Android SDK: `sdk.dir` from a project's `local.properties`, then `$ANDROID_HOME`/
         * `$ANDROID_SDK_ROOT`, then the conventional per-OS install dirs. Returns the first that exists.
         */
        fun findSdkRoot(projectDir: Path? = null): Path? {
            projectDir?.resolve("local.properties")?.takeIf { Files.isRegularFile(it) }?.let { props ->
                Files.readAllLines(props).firstNotNullOfOrNull { line ->
                    line.trim().takeIf { l -> l.startsWith("sdk.dir=") }?.substringAfter('=')?.trim()
                }?.let { return Paths.get(it.replace("\\:", ":").replace("\\\\", "\\")) }
            }
            val home = System.getProperty("user.home").orEmpty()
            return sequenceOf(
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
                "$home/Library/Android/sdk",   // macOS
                "$home/Android/Sdk",           // Linux
                "$home/AppData/Local/Android/Sdk", // Windows
            ).filterNotNull().map { Paths.get(it) }.firstOrNull { Files.isDirectory(it.resolve("platforms")) }
        }

        /**
         * The installed framework SOURCES dir for the platform named [platformDirName] (`android-36`): the
         * exact-named `sources/android-36`, else any installed `sources/android-NN…` with the same MAJOR API
         * level. The SDK keys framework sources by base level while the platform jar can be a minor / extension
         * revision (`android-36.1`), so an exact-only match misses sources the editor can perfectly well use
         * for parameter names + javadoc. Null when no same-major sources are installed.
         */
        fun platformSourcesDir(sdkRoot: Path, platformDirName: String): Path? {
            val sources = sdkRoot.resolve("sources")
            if (!Files.isDirectory(sources)) return null
            val exact = sources.resolve(platformDirName)
            if (Files.isDirectory(exact)) return exact
            val major = apiLevelOf(platformDirName)
            if (major < 0) return null
            return Files.list(sources).use { stream ->
                stream.filter { Files.isDirectory(it) && apiLevelOf(it.fileName.toString()) == major }
                    .sorted(compareByDescending { it.fileName.toString() })
                    .findFirst().orElse(null)
            }
        }

        private fun locatePlatformJar(platformsDir: Path, compileSdk: Int?): Path? {
            if (!Files.isDirectory(platformsDir)) return null
            if (compileSdk != null) {
                val exact = platformsDir.resolve("android-$compileSdk").resolve("android.jar")
                if (Files.exists(exact)) return exact
            }
            return Files.list(platformsDir).use { stream ->
                stream.map { it.resolve("android.jar") }.filter { Files.exists(it) }
                    .sorted(compareByDescending { apiLevelOf(it.parent.fileName.toString()) })
                    .findFirst().orElse(null)
            }
        }

        private fun locateBuildTools(buildToolsDir: Path): Path? {
            if (!Files.isDirectory(buildToolsDir)) return null
            return Files.list(buildToolsDir).use { stream ->
                stream.filter { Files.exists(it.resolve(if (IS_WINDOWS) "aapt2.exe" else "aapt2")) }
                    .sorted(compareByDescending { versionKey(it.fileName.toString()) })
                    .findFirst().orElse(null)
            }
        }

        /** `android-34` -> 34, `android-36.1` -> 36 (preview/extension minor ignored for ordering). */
        private fun apiLevelOf(name: String): Int =
            name.removePrefix("android-").substringBefore('.').toIntOrNull() ?: -1

        /** Order `36.1.0` > `36.0.0` > `33.0.2` by numeric components, as a single comparable key. */
        private fun versionKey(name: String): Long =
            name.split('.').take(3).fold(0L) { acc, c -> acc * 10_000 + (c.toIntOrNull() ?: 0).coerceIn(0, 9_999) }
    }
}
