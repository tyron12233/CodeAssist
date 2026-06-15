package dev.ide.android

import android.content.Context
import dev.ide.core.IdeServices
import dev.ide.core.IdeServicesBackend
import dev.ide.core.ProjectManager
import java.io.File
import java.nio.file.Path

/**
 * On-device bootstrap for the IDE engine, the Android counterpart to :ide-desktop's wiring. ART has no
 * JDK to detect, so the Android SDK's `android.jar` (signatures for `java.*` + `android.*`) ships as an
 * asset, is copied into app storage once, and is fed as each workspace's boot classpath. Projects live
 * under `filesDir/codeassist/projects` (one workspace dir each) and a [ProjectManager] creates/opens/lists
 * them, so the IDE supports live in-session switching. On first launch the Android multi-module sample is
 * seeded. Everything above that (project model, JDT completion/analysis, indexing, the Android build) is
 * the same `IdeServices` the desktop runs, surfaced through the same `IdeServicesBackend`.
 */
object AndroidIde {

    /** Heavy (file copy + project gen + JDT init) — call off the main thread. */
    fun bootstrap(context: Context): Session {
        val home = File(context.filesDir, "codeassist").apply { mkdirs() }
        val androidJar = copyAsset(context, "android.jar", File(home, "android.jar"))
        // The debug keystore is a shared, non-secret credential; ART has no `keytool`, so a prebuilt
        // PKCS12 keystore ships as an asset and is copied out for apksig (in-process) to sign with.
        val debugKeystore = copyAsset(context, "debug.keystore", File(home, "debug.keystore"))
        // The aapt2/zipalign prebuilts are packaged as lib*.so and extracted here at install time — the only
        // directory ART permits executing binaries from.
        val nativeLibDir = Path.of(context.applicationInfo.nativeLibraryDir)

        val projectsRoot = File(home, "projects").toPath()
        val bootClasspath = listOf(androidJar.absolutePath)
        // Runs a dexed Java console app in-process (ART has no `java` to fork); the oat cache is transient.
        val dexRunner = DexClassLoaderRunner(File(context.cacheDir, "dexrun"))
        // Installs + launches a built APK (the android Run) via the system package installer.
        val apkInstaller = ApkInstallerImpl(context)
        // dataDir = the whole app filesDir, so a backup also sweeps up project files left by a previous
        // (incompatible) app version that still live in app storage after the update.
        val manager = ProjectManager.onDevice(
            projectsRoot, bootClasspath, nativeLibDir, debugKeystore.toPath(),
            dataDir = context.filesDir.toPath(),
            dexRunner = dexRunner,
            deviceApiLevel = android.os.Build.VERSION.SDK_INT,
            apkInstaller = apkInstaller,
        )

        val services = if (manager.isEmpty()) {
            // Seed the rich Android multi-module sample (app → feature → core) into its own workspace dir.
            // Java 8 level: a bundled non-modular android.jar keeps JDT DOM analysis working on ART.
            IdeServices.bootstrapWithBootClasspath(
                root = projectsRoot.resolve("android-sample"),
                bootClasspath = bootClasspath,
                sdkName = "android-36",
                generateDemo = true,
                androidToolsDir = nativeLibDir,
                debugKeystore = debugKeystore.toPath(),
                dexRunner = dexRunner,
                deviceApiLevel = android.os.Build.VERSION.SDK_INT,
                apkInstaller = apkInstaller,
            )
        } else {
            manager.open(manager.list().first().rootPath)
        }

        return Session(services, IdeServicesBackend(services, manager))
    }

    /** Copy a bundled asset into app storage once (assets are read-only in the APK). */
    private fun copyAsset(context: Context, name: String, dest: File): File {
        if (!dest.exists() || dest.length() == 0L) {
            context.assets.open(name).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    /** The live engine + its UI-port adapter; [backend] is held so the Activity can close it on teardown. */
    class Session(val services: IdeServices, val backend: IdeServicesBackend)
}
