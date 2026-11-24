package dev.codeassist.dart

import dev.ide.model.BuildSystemId
import dev.ide.model.DependencyScope
import dev.ide.model.sync.Detection
import dev.ide.model.sync.ExternalDependency
import dev.ide.model.sync.ExternalFacet
import dev.ide.model.sync.ExternalLibrary
import dev.ide.model.sync.ExternalModule
import dev.ide.model.sync.ExternalModuleRef
import dev.ide.model.sync.ExternalProjectModel
import dev.ide.model.sync.ExternalRepository
import dev.ide.model.sync.ExternalSourceSet
import dev.ide.model.sync.ModelOwnership
import dev.ide.model.sync.ProjectImporter
import dev.ide.model.sync.SyncMessage
import dev.ide.model.sync.SyncOutcome
import dev.ide.model.sync.SyncRequest
import dev.ide.model.sync.SyncSeverity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens a pub package as a Dart project: the package as a module, rooted where its `pubspec.yaml` is.
 *
 * A Flutter app with an Android side (`android/app`, as `flutter create` and the Flutter App template lay it
 * out) also gets that as an Android app module, built with the Flutter app in it ([FlutterBuildPlugin]), and
 * one Android library module per Flutter plugin with Android code its dependencies reach, as the Flutter Gradle
 * plugin includes each one as a Gradle subproject. Which plugins there are is decided by `pub get`, not by
 * anything the user edits, so this syncs before a build whenever the package config changed
 * ([syncBeforeBuild]), and a sync removes only the modules an earlier sync produced.
 *
 * The IDE owns the model once the package is adopted ([ModelOwnership.IDE]): a dependency the user adds to the
 * app module in the IDE survives the syncs that keep its plugin modules current.
 */
object PubImporter : ProjectImporter {
    override val id: BuildSystemId = DartModuleType.PUB
    override val displayName = "Dart (pub)"
    override val ownership: ModelOwnership get() = ModelOwnership.IDE
    override val syncBeforeBuild: Boolean get() = true

    /** The Android app module of a Flutter app, where `flutter create` puts it. */
    const val ANDROID_APP_DIR = "android/app"
    const val ANDROID_APP_MODULE = "app"

    override fun detect(root: Path): Detection? {
        val pubspec = root.resolve(DartPackages.PUBSPEC)
        if (!Files.isRegularFile(pubspec)) return null
        return Detection(nameOf(root, pubspec), listOf(pubspec), confidence = 10)
    }

    /** What changes the modules: the package config (`pub get`, so the plugins), and an Android app's build file. */
    override fun syncFiles(): List<String> = listOf(
        DartPackages.PUBSPEC,
        DartPackages.PACKAGE_CONFIG,
        "$ANDROID_APP_DIR/build.gradle",
        "$ANDROID_APP_DIR/build.gradle.kts",
    )

    override suspend fun resolve(request: SyncRequest): SyncOutcome = withContext(Dispatchers.IO) {
        val root = request.root
        val pubspec = root.resolve(DartPackages.PUBSPEC)
        if (!Files.isRegularFile(pubspec)) return@withContext SyncOutcome.failed("No pubspec.yaml at ${root}")
        val name = nameOf(root, pubspec)
        val messages = ArrayList<SyncMessage>()
        val dart = ExternalModule(
            name = name,
            // The project root, written as "." because the host reads a blank path as "a directory named after
            // the module": an imported package's module then sat in `<root>/<name>/`, where there is no pubspec.
            dirRelPath = ".",
            typeId = DartModuleType.ID,
            sourceSets = DartPackages.contentRoots(root).map { (set, dirs) ->
                ExternalSourceSet(
                    set,
                    scope = if (set == "test") DependencyScope.TEST_IMPLEMENTATION else DependencyScope.IMPLEMENTATION,
                    roots = dirs.associateWith { setOf(DartModuleType.DART_SOURCE) },
                )
            },
        )
        val android = if (isFlutterApp(root)) androidModules(root, messages) else emptyList()
        val repositories = if (android.isEmpty()) emptyList()
        else listOf(ExternalRepository("Flutter", FlutterSdk.MAVEN_REPOSITORY))
        SyncOutcome(ExternalProjectModel(name, DartModuleType.PUB, modules = listOf(dart) + android, repositories = repositories), messages)
    }

    private fun isFlutterApp(root: Path): Boolean =
        FlutterSdk.isFlutterPackage(root) && Files.isRegularFile(root.resolve("$ANDROID_APP_DIR/src/main/AndroidManifest.xml"))

    /**
     * The Android app module and its plugins' library modules. Runs `pub get` first when the package config is
     * missing or stale, since which plugins there are is what it decides; without one, the app module comes
     * without plugins and a message says why.
     */
    private fun androidModules(root: Path, messages: MutableList<SyncMessage>): List<ExternalModule> {
        if (DartPackages.needsPubGet(root)) {
            val pub = DartPlugin.tools?.pub
            val output = StringBuilder()
            val code = pub?.run(root, "get", out = { output.appendLine(it) }) ?: -1
            if (code != 0 || DartPackages.needsPubGet(root)) {
                messages += SyncMessage(
                    SyncSeverity.WARNING,
                    "pub get failed, so the app's Flutter plugins are not known yet: ${output.toString().trim().takeLast(600)}",
                )
            }
        }
        val plugins = runCatching { FlutterPlugins.discover(root) }.getOrDefault(emptyList())
        val withCode = plugins.filter { it.hasAndroidCode }
        val abi = FlutterApkTask.abiOf(DartPlugin.tools?.flutter?.engine?.parent)

        val libraries = withCode.mapNotNull { plugin ->
            runCatching { pluginModule(root, plugin, abi, messages) }
                .onFailure { messages += SyncMessage(SyncSeverity.WARNING, "The Flutter plugin ${plugin.name} could not be set up: ${it.message}") }
                .getOrNull()
        }
        // The Java registrant names exactly the plugins whose modules exist.
        val registered = withCode.filter { p -> libraries.any { it.name == p.name } }
        runCatching {
            val file = root.resolve("$ANDROID_APP_DIR/src/main/java/io/flutter/plugins/GeneratedPluginRegistrant.java")
            val text = FlutterPlugins.javaRegistrant(registered)
            if (!Files.isRegularFile(file) || String(Files.readAllBytes(file)) != text) {
                Files.createDirectories(file.parent)
                Files.write(file, text.toByteArray())
            }
        }.onFailure { messages += SyncMessage(SyncSeverity.WARNING, "GeneratedPluginRegistrant.java could not be written: ${it.message}") }

        val appBuildText = appBuildFile(root)?.let { String(Files.readAllBytes(it)) }
        val appBuild = appBuildText?.let { FlutterPlugins.parseAppBuild(it) }
        val appDependencies = FlutterAppTemplate.EMBEDDINGS.map { (variant, coordinate) ->
            ExternalLibrary(coordinate, DependencyScope.IMPLEMENTATION, variant)
        } + libraries.map { ExternalModuleRef(it.name, DependencyScope.IMPLEMENTATION) } +
            appBuild?.dependencies.orEmpty().map { (configuration, coordinate) -> ExternalLibrary(coordinate, scopeOf(configuration)) }
        appBuild?.unreadDependencies?.forEach {
            messages += SyncMessage(SyncSeverity.WARNING, "android/app: could not read the dependency '$it' in its build file.")
        }
        val facets = ArrayList<ExternalFacet>()
        facets += ExternalFacet(FlutterFacetCodec.tomlTable, FlutterFacetCodec.encode(FlutterFacet()))
        // A project `flutter create` made has its Android settings in its Gradle build file; one the Flutter App
        // template made has them in the module itself, which a sync then leaves as they are.
        // Only when the build file changed since the settings were last taken from it (or never were): a change
        // made since in Module Settings then stands until the build file is edited again, whichever was edited
        // last wins, rather than every sync (each pub get) quietly putting the build file's values back.
        if (appBuild != null && appBuildChanged(root, appBuildText!!)) facets += ExternalFacet("android", androidSettings(root, appBuild))
        val app = ExternalModule(
            name = ANDROID_APP_MODULE,
            dirRelPath = ANDROID_APP_DIR,
            typeId = "android-app",
            dependencies = appDependencies,
            facets = facets,
        )
        return listOf(app) + libraries
    }

    /**
     * Whether [text], the app's build file, differs from the one whose settings the last sync applied, recording
     * it as applied. True the first time, and whenever the stamp is unreadable: better a settings refresh than
     * settings that never came from the build file at all.
     */
    private fun appBuildChanged(root: Path, text: String): Boolean {
        val stamp = root.resolve(".dart_tool/codeassist/android-app-build.sha256")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        val previous = runCatching { String(Files.readAllBytes(stamp)).trim() }.getOrNull()
        if (previous == digest) return false
        runCatching {
            Files.createDirectories(stamp.parent)
            Files.write(stamp, digest.toByteArray())
        }
        return true
    }

    private fun appBuildFile(root: Path): Path? =
        listOf("build.gradle.kts", "build.gradle").map { root.resolve("$ANDROID_APP_DIR/$it") }.firstOrNull { Files.isRegularFile(it) }

    /**
     * The `[android]` table for an app whose build file says it, with what the Flutter Gradle plugin supplies
     * filled in: its default SDK levels for `flutter.compileSdkVersion` and the like, the version from the
     * pubspec for `flutter.versionCode` / `flutter.versionName`, and the `applicationName` placeholder the
     * manifest of every `flutter create` app names.
     */
    internal fun androidSettings(root: Path, build: AppAndroidBuild): Map<String, Any?> {
        val version = runCatching {
            (MiniYaml.parse(String(Files.readAllBytes(root.resolve(DartPackages.PUBSPEC)))) as? Map<*, *>)?.get("version")?.toString()
        }.getOrNull()
        val namespace = build.namespace ?: build.applicationId ?: "com.example.${nameOf(root, root.resolve(DartPackages.PUBSPEC))}"
        return mapOf(
            "namespace" to namespace,
            "applicationId" to (build.applicationId ?: namespace),
            "compileSdk" to (build.compileSdk ?: FLUTTER_COMPILE_SDK),
            "minSdk" to (build.minSdk ?: FLUTTER_MIN_SDK),
            "targetSdk" to (build.targetSdk ?: FLUTTER_TARGET_SDK),
            "versionCode" to (build.versionCode ?: versionCode(version)),
            "versionName" to (build.versionName ?: versionName(version)),
            "manifestPlaceholders" to listOf("applicationName=android.app.Application"),
        )
    }

    /** `1.2.0+7` gives version code 7, as Flutter reads a pubspec's version; no build number gives 1. */
    internal fun versionCode(version: String?): Int = version?.substringAfter('+', "")?.toIntOrNull() ?: 1

    /** `1.2.0+7` gives version name `1.2.0`. */
    internal fun versionName(version: String?): String = version?.substringBefore('+')?.takeIf { it.isNotBlank() } ?: "1.0.0"

    /** The Flutter Gradle plugin's defaults for Flutter [FlutterSdk.VERSION] (its FlutterExtension). */
    const val FLUTTER_COMPILE_SDK = 36
    const val FLUTTER_MIN_SDK = 24
    const val FLUTTER_TARGET_SDK = 36

    private fun scopeOf(configuration: String) = when (configuration) {
        "api" -> DependencyScope.API
        "compileOnly" -> DependencyScope.COMPILE_ONLY
        "runtimeOnly" -> DependencyScope.RUNTIME_ONLY
        else -> DependencyScope.IMPLEMENTATION
    }

    /** [plugin]'s Android library as a module: its code copied into the project, its build read for the rest. */
    private fun pluginModule(root: Path, plugin: FlutterPlugin, abi: String?, messages: MutableList<SyncMessage>): ExternalModule {
        val androidDir = plugin.root.resolve("android")
        val build = FlutterPlugins.readAndroidBuild(androidDir)
        val cmake = build.cmakePath?.let { path ->
            val file = androidDir.resolve(path).normalize()
            runCatching { FlutterPlugins.parseCMake(String(Files.readAllBytes(file))) }.getOrNull()
        }
        if (plugin.ffi && cmake == null) {
            messages += SyncMessage(SyncSeverity.WARNING, "${plugin.name} has native code whose build could not be read, so it is not built.")
        }
        val dirRel = FlutterPlugins.moduleDirRel(plugin)
        FlutterPlugins.copyAndroidLibrary(plugin, build, cmake, root.resolve(dirRel))
        for (unread in build.unreadDependencies) {
            messages += SyncMessage(SyncSeverity.WARNING, "${plugin.name}: could not read the dependency '$unread' in its build file.")
        }

        val dependencies = ArrayList<ExternalDependency>()
        for ((configuration, coordinate) in build.dependencies) dependencies += ExternalLibrary(coordinate, scopeOf(configuration))
        // The engine's Java half to compile against; the app packages its own, debug or release.
        dependencies += ExternalLibrary(FlutterSdk.EMBEDDING_DEBUG, DependencyScope.COMPILE_ONLY)
        plugin.dependsOn.forEach { dependencies += ExternalModuleRef(it, DependencyScope.IMPLEMENTATION) }

        val namespace = build.namespace ?: plugin.androidPackage ?: "io.flutter.plugins.${plugin.name}"
        val facets = ArrayList<ExternalFacet>()
        facets += ExternalFacet(
            "android",
            mapOf(
                "namespace" to namespace,
                "compileSdk" to FlutterAppTemplate.COMPILE_SDK,
                "minSdk" to (build.minSdk ?: 21).coerceAtMost(FlutterAppTemplate.MIN_SDK),
                "targetSdk" to FlutterAppTemplate.COMPILE_SDK,
                "isApplication" to false,
            ),
        )
        if (cmake != null) {
            // The NDK plugin's table, by name: compiled by its on-device clang, packaged by the app's build.
            facets += ExternalFacet(
                "ndk",
                mapOf(
                    "sourceDirs" to listOf("src/main/cpp"),
                    "libraryName" to cmake.outputName,
                    "abis" to listOfNotNull(abi).ifEmpty { listOf("arm64-v8a") },
                    "minSdk" to FlutterAppTemplate.MIN_SDK,
                    "compilerFlags" to cmake.definitions.map { "-D$it" },
                    "linkLibraries" to cmake.linkLibraries.ifEmpty { listOf("log") },
                ),
            )
        }
        return ExternalModule(
            name = plugin.name,
            dirRelPath = dirRel,
            typeId = "android-lib",
            dependencies = dependencies,
            facets = facets,
        )
    }

    private fun nameOf(root: Path, pubspec: Path): String =
        runCatching { DartPackages.nameOf(String(Files.readAllBytes(pubspec))) }.getOrNull()
            ?: DartPackages.packageName(root.fileName?.toString() ?: "dart")
}
