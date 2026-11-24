package dev.codeassist.dart

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.stream.Collectors

/**
 * A Flutter plugin the app depends on, as its pubspec's `flutter: plugin:` section describes it for Android.
 *
 * Three kinds matter here, alone or together: [pluginClass] is Java or Kotlin code under the package's `android/`
 * that the app registers with its engine; [ffi] is native code built by CMake and loaded from Dart; and
 * [dartPluginClass] is a Dart class registered at startup (a federated plugin's Android implementation, which
 * may have no Android code at all and reach Android through `jni` instead).
 */
data class FlutterPlugin(
    val name: String,
    /** The package's root (where its pubspec is). */
    val root: Path,
    val androidPackage: String? = null,
    val pluginClass: String? = null,
    val dartPluginClass: String? = null,
    /** `package:<name>/<file>` the Dart class is in: `dartPluginFile`, else `<name>.dart`. */
    val dartPluginFile: String? = null,
    val ffi: Boolean = false,
    /** The interface package this implements (`implements:`), for choosing one implementation per interface. */
    val implements: String? = null,
    /** The other Android plugins this one depends on, by package name. */
    val dependsOn: List<String> = emptyList(),
) {
    /** The package has an Android library to build: code to register, or native code to compile. */
    val hasAndroidCode: Boolean get() = (pluginClass != null || ffi) && Files.isDirectory(root.resolve("android"))
}

/** What a plugin's `android/build.gradle(.kts)` says, as far as it can be read without running Gradle. */
data class PluginAndroidBuild(
    val namespace: String? = null,
    val minSdk: Int? = null,
    /** `configuration` to `group:name:version`: implementation, api, compileOnly, runtimeOnly. */
    val dependencies: List<Pair<String, String>> = emptyList(),
    /** Declarations that could not be read (a version catalog entry, an interpolated version). */
    val unreadDependencies: List<String> = emptyList(),
    /** Java source directories besides `src/main/java`, relative to `android/` (`../java/src/main/java`). */
    val extraJavaDirs: List<String> = emptyList(),
    /** `externalNativeBuild { cmake { path } }`, relative to `android/`. */
    val cmakePath: String? = null,
)

/**
 * What a Flutter app's `android/app/build.gradle(.kts)` says. A value written as `flutter.<x>` (the Flutter Gradle
 * plugin's default) reads as null: the caller fills in the default, as that plugin would.
 */
data class AppAndroidBuild(
    val namespace: String? = null,
    val applicationId: String? = null,
    val compileSdk: Int? = null,
    val minSdk: Int? = null,
    val targetSdk: Int? = null,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val dependencies: List<Pair<String, String>> = emptyList(),
    val unreadDependencies: List<String> = emptyList(),
)

/** One `add_library(... SHARED ...)` of a plugin's CMakeLists.txt, which is all a Flutter FFI plugin's usually is. */
data class CMakeLibrary(
    val target: String,
    val outputName: String,
    /** Source files relative to the CMakeLists' directory. */
    val sources: List<String>,
    val definitions: List<String> = emptyList(),
    val linkLibraries: List<String> = emptyList(),
)

object FlutterPlugins {

    /**
     * The plugins of the app at [pkg] (whose `pub get` has run), sorted by name as `flutter` lists them: every
     * package its non-dev dependencies reach whose pubspec declares an Android plugin. A dev dependency's plugin
     * (a test helper) does not belong in the app.
     */
    fun discover(pkg: Path): List<FlutterPlugin> {
        val roots = DartPackages.packages(pkg).toMap()
        val pubspecs = HashMap<String, Map<*, *>>()
        fun pubspec(name: String): Map<*, *>? = pubspecs.getOrPut(name) {
            val root = roots[name] ?: return null
            (runCatching { MiniYaml.parse(String(Files.readAllBytes(root.resolve(DartPackages.PUBSPEC)))) }.getOrNull() as? Map<*, *>)
                ?: emptyMap<Any, Any>()
        }
        fun dependencies(spec: Map<*, *>?): List<String> =
            (spec?.get("dependencies") as? Map<*, *>)?.keys?.map { it.toString() }.orEmpty()

        val appSpec = runCatching { MiniYaml.parse(String(Files.readAllBytes(pkg.resolve(DartPackages.PUBSPEC)))) }.getOrNull() as? Map<*, *>
        val appName = appSpec?.get("name")?.toString()
        // Everything the app's own dependencies reach, dev dependencies left out.
        val reached = LinkedHashSet<String>()
        val queue = ArrayDeque(dependencies(appSpec))
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name == appName || name !in roots || !reached.add(name)) continue
            queue.addAll(dependencies(pubspec(name)))
        }

        val plugins = reached.mapNotNull { name -> describe(name, roots.getValue(name), pubspec(name) ?: return@mapNotNull null) }
        val android = plugins.filter { it.hasAndroidCode }.map { it.name }.toSet()
        return plugins
            .map { p -> p.copy(dependsOn = dependencies(pubspec(p.name)).filter { it in android && it != p.name }.sorted()) }
            .sortedBy { it.name }
    }

    /** [spec]'s Android plugin, or null when the package is not one (or has nothing for Android). */
    fun describe(name: String, root: Path, spec: Map<*, *>): FlutterPlugin? {
        val plugin = (spec["flutter"] as? Map<*, *>)?.get("plugin") as? Map<*, *> ?: return null
        val implements = plugin["implements"]?.toString()
        val android = (plugin["platforms"] as? Map<*, *>)?.get("android") as? Map<*, *>
        if (android == null) {
            // The format before `platforms:`, Android only.
            val pkg = plugin["androidPackage"]?.toString() ?: return null
            return FlutterPlugin(name, root, androidPackage = pkg, pluginClass = plugin["pluginClass"]?.toString(), implements = implements)
        }
        val pluginClass = android["pluginClass"]?.toString()?.takeIf { it.isNotBlank() && it != "none" }
        val dartPluginClass = android["dartPluginClass"]?.toString()?.takeIf { it.isNotBlank() }
        val ffi = android["ffiPlugin"]?.toString() == "true"
        if (pluginClass == null && dartPluginClass == null && !ffi) return null
        return FlutterPlugin(
            name, root,
            androidPackage = android["package"]?.toString(),
            pluginClass = pluginClass,
            dartPluginClass = dartPluginClass,
            dartPluginFile = android["dartPluginFile"]?.toString(),
            ffi = ffi,
            implements = implements,
        )
    }

    // ---- reading a plugin's Android build ----

    fun readAndroidBuild(androidDir: Path): PluginAndroidBuild {
        val file = listOf("build.gradle.kts", "build.gradle").map { androidDir.resolve(it) }.firstOrNull { Files.isRegularFile(it) }
            ?: return PluginAndroidBuild()
        return parseAndroidBuild(String(Files.readAllBytes(file)))
    }

    private val CONFIGURATIONS = setOf("implementation", "api", "compileOnly", "runtimeOnly")

    fun parseAndroidBuild(text: String): PluginAndroidBuild {
        val code = stripComments(text)
        val namespace = Regex("""\bnamespace\s*(?:=\s*)?["']([\w.]+)["']""").find(code)?.groupValues?.get(1)
        val minSdk = Regex("""\bminSdk(?:Version)?\s*(?:=\s*)?(?:\(\s*)?(\d+)""").find(code)?.groupValues?.get(1)?.toIntOrNull()
        val deps = ArrayList<Pair<String, String>>()
        val unread = ArrayList<String>()
        // `implementation("g:a:v")`, `implementation 'g:a:v'`; anything else on such a line is reported, not guessed.
        for (m in Regex("""(?m)^\s*(\w+)\s*\(?\s*(.+?)\s*\)?\s*$""").findAll(code)) {
            val configuration = m.groupValues[1]
            if (configuration !in CONFIGURATIONS) continue
            val argument = m.groupValues[2]
            val literal = Regex("""^["']([^"'\s]+:[^"'\s]+:[^"'\s]+)["']$""").find(argument)?.groupValues?.get(1)
            if (literal != null && '$' !in literal) deps += configuration to literal
            else if (!argument.startsWith("project(") && !argument.startsWith("files(") && !argument.startsWith("fileTree(")) {
                unread += "$configuration $argument"
            }
        }
        // `java.srcDirs += '../x'`, `java { srcDirs '../x' }`, `java.srcDir("../x")`.
        val javaDirs = Regex("""java[\s\S]{0,40}?srcDirs?\s*(?:\+=|=)?\s*\(?\s*(?:\[|listOf\(|setOf\()?\s*["']([^"']+)["']""")
            .findAll(code).map { it.groupValues[1] }.filter { it != "src/main/java" }.distinct().toList()
        val cmake = Regex("""cmake\s*\{[\s\S]*?\bpath\s*(?:=\s*)?(?:file\(\s*)?["']([^"']*CMakeLists\.txt)["']""").find(code)?.groupValues?.get(1)
        return PluginAndroidBuild(namespace, minSdk, deps, unread, javaDirs, cmake)
    }

    fun parseAppBuild(text: String): AppAndroidBuild {
        val code = stripComments(text)
        val common = parseAndroidBuild(text)
        fun int(name: String): Int? =
            Regex("""\b$name(?:Version)?\s*(?:=\s*)?(?:\(\s*)?(\d+)""").find(code)?.groupValues?.get(1)?.toIntOrNull()
        fun string(name: String): String? =
            Regex("""\b$name\s*(?:=\s*)?["']([^"'$]+)["']""").find(code)?.groupValues?.get(1)
        return AppAndroidBuild(
            namespace = common.namespace,
            applicationId = string("applicationId"),
            compileSdk = int("compileSdk"),
            minSdk = common.minSdk,
            targetSdk = int("targetSdk"),
            versionCode = int("versionCode"),
            versionName = string("versionName"),
            dependencies = common.dependencies,
            unreadDependencies = common.unreadDependencies,
        )
    }

    /** The first SHARED library [text] (a CMakeLists.txt) adds, with what it is compiled and linked with. */
    fun parseCMake(text: String): CMakeLibrary? {
        val code = text.lineSequence().map { it.substringBefore('#') }.joinToString("\n")
        val lib = Regex("""add_library\s*\(\s*([\w.-]+)\s+SHARED\s+([^)]*)\)""").find(code) ?: return null
        val target = lib.groupValues[1]
        fun arguments(body: String) = Regex(""""([^"]*)"|(\S+)""").findAll(body).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        val sources = arguments(lib.groupValues[2]).filter { !it.startsWith("\${") && it.isNotBlank() }
        val outputName = Regex("""set_target_properties\s*\(\s*${Regex.escape(target)}\b[^)]*OUTPUT_NAME\s+"?([\w.-]+)"?""").find(code)?.groupValues?.get(1) ?: target
        val keywords = setOf("PUBLIC", "PRIVATE", "INTERFACE")
        val definitions = Regex("""target_compile_definitions\s*\(\s*${Regex.escape(target)}\s+([^)]*)\)""").findAll(code)
            .flatMap { arguments(it.groupValues[1]) }.filter { it !in keywords && !it.startsWith("\${") }.toList()
        // Only the Android branch's libraries count, and only system ones (`log`, `android`, `m`): a CMake
        // variable is for the desktop builds the same file serves.
        val links = Regex("""target_link_libraries\s*\(\s*${Regex.escape(target)}\s+([^)]*)\)""").findAll(code)
            .flatMap { arguments(it.groupValues[1]) }.filter { it !in keywords && Regex("""[a-z0-9_]+""").matches(it) }.distinct().toList()
        return CMakeLibrary(target, outputName, sources, definitions, links)
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").lineSequence().map { line ->
            val at = Regex("""(^|[^:"'])//""").find(line)?.range?.first
            if (at == null) line else line.substring(0, at + if (line[at] == '/') 0 else 1)
        }.joinToString("\n")

    // ---- the plugin's Android library, inside the app's project ----

    /** Where [plugin]'s Android library lives in the project at [pkg]: a copy, laid out as one module. */
    fun moduleDirRel(plugin: FlutterPlugin) = ".dart_tool/codeassist/android-plugins/${plugin.name}"

    /**
     * Copy [plugin]'s Android library into [dest], laid out as a plain Android library module: `src/main` from
     * `android/src/main`, any extra Java directories its build names merged into `src/main/java`, and its CMake
     * library's sources (with every header beside them) under `src/main/cpp`, where the NDK plugin builds it.
     * A copy rather than the pub cache in place, because a module lives inside its project, and the pub cache
     * is shared by every project and owned by pub. Skipped when [dest] already holds this version.
     */
    fun copyAndroidLibrary(plugin: FlutterPlugin, build: PluginAndroidBuild, cmake: CMakeLibrary?, dest: Path) {
        val stamp = dest.resolve(".source")
        val source = plugin.root.toString()
        if (Files.isRegularFile(stamp) && String(Files.readAllBytes(stamp)) == source) return
        if (Files.exists(dest)) dest.toFile().deleteRecursively()
        val android = plugin.root.resolve("android")
        copyTree(android.resolve("src/main"), dest.resolve("src/main"))
        for (dir in build.extraJavaDirs) copyTree(android.resolve(dir).normalize(), dest.resolve("src/main/java"))
        if (cmake != null && build.cmakePath != null) {
            val cmakeDir = android.resolve(build.cmakePath).normalize().parent
            val cpp = dest.resolve("src/main/cpp")
            for (file in cmake.sources) copyFile(cmakeDir.resolve(file).normalize(), cpp.resolve(file).normalize())
            Files.walk(cmakeDir).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".h") }.collect(Collectors.toList()) }
                .forEach { copyFile(it, cpp.resolve(cmakeDir.relativize(it).toString())) }
        }
        Files.createDirectories(dest)
        Files.write(stamp, source.toByteArray())
    }

    private fun copyTree(from: Path, to: Path) {
        if (!Files.isDirectory(from)) return
        Files.walk(from).use { s -> s.filter { Files.isRegularFile(it) }.collect(Collectors.toList()) }
            .forEach { copyFile(it, to.resolve(from.relativize(it).toString())) }
    }

    private fun copyFile(from: Path, to: Path) {
        if (!Files.isRegularFile(from)) return
        Files.createDirectories(to.parent)
        Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
    }

    // ---- registrants ----

    /**
     * `GeneratedPluginRegistrant.java`, which `FlutterActivity` registers the app's plugins through: one `add` per
     * plugin with a [FlutterPlugin.pluginClass], each failing alone, as `flutter` writes it.
     */
    fun javaRegistrant(plugins: List<FlutterPlugin>): String = buildString {
        append(
            """
            package io.flutter.plugins;

            import androidx.annotation.Keep;
            import androidx.annotation.NonNull;
            import io.flutter.Log;

            import io.flutter.embedding.engine.FlutterEngine;

            /**
             * Generated file. Do not edit.
             * CodeAssist writes it from the app's Flutter plugins that support Android, as the Flutter tool does.
             */
            @Keep
            public final class GeneratedPluginRegistrant {
              private static final String TAG = "GeneratedPluginRegistrant";
              public static void registerWith(@NonNull FlutterEngine flutterEngine) {

            """.trimIndent(),
        )
        for (p in plugins.filter { it.pluginClass != null && it.androidPackage != null }) {
            val cls = "${p.androidPackage}.${p.pluginClass}"
            append("    try {\n")
            append("      flutterEngine.getPlugins().add(new $cls());\n")
            append("    } catch (Exception e) {\n")
            append("      Log.e(TAG, \"Error registering plugin ${p.name}, $cls\", e);\n")
            append("    }\n")
        }
        append("  }\n}\n")
    }

    /**
     * The Dart plugin registrant: the one Android implementation per interface that registers itself in Dart
     * (`dartPluginClass`), called by the engine before `main`. The app's direct dependency wins over a default
     * implementation, as `flutter` decides it. Null when no plugin has one.
     */
    fun dartRegistrant(plugins: List<FlutterPlugin>, directDependencies: Set<String>): String? {
        val chosen = plugins.filter { it.dartPluginClass != null }
            .groupBy { it.implements ?: it.name }
            .map { (_, candidates) -> candidates.firstOrNull { it.name in directDependencies } ?: candidates.first() }
            .sortedBy { it.name }
        if (chosen.isEmpty()) return null
        return buildString {
            append("// Generated file. Do not edit. CodeAssist writes it from the app's Flutter plugins, as the Flutter tool does.\n")
            append("// ignore_for_file: type=lint\n\n")
            append("import 'dart:io'; // flutter_ignore: dart_io_import.\n")
            for (p in chosen) append("import 'package:${p.name}/${p.dartPluginFile ?: "${p.name}.dart"}' as ${p.name};\n")
            append("\n@pragma('vm:entry-point')\nclass _PluginRegistrant {\n\n  @pragma('vm:entry-point')\n  static void register() {\n")
            append("    if (Platform.isAndroid) {\n")
            for (p in chosen) {
                append("      try {\n")
                append("        ${p.name}.${p.dartPluginClass}.registerWith();\n")
                append("      } catch (err) {\n")
                append("        print('`${p.name}` threw an error: \$err. The app may not function as expected until you remove this plugin from pubspec.yaml');\n")
                append("      }\n")
            }
            append("    }\n  }\n}\n")
        }
    }

    /** Where the Dart registrant is written, as `flutter` places it. */
    fun dartRegistrantFile(pkg: Path): Path = pkg.resolve(".dart_tool/flutter_build/dart_plugin_registrant.dart")

    /**
     * The compiler arguments that include [registrant] in the program and tell the engine where it is, as
     * `flutter` passes them to the frontend server.
     */
    fun dartRegistrantArgs(registrant: Path): List<String> {
        val uri = registrant.toUri().toString()
        return listOf(
            "--source", uri,
            "--source", "package:flutter/src/dart_plugin_registrant.dart",
            "-Dflutter.dart_plugin_registrant=$uri",
        )
    }

    /** The app's direct (non-dev) dependencies, by name. */
    fun directDependencies(pkg: Path): Set<String> {
        val spec = runCatching { MiniYaml.parse(String(Files.readAllBytes(pkg.resolve(DartPackages.PUBSPEC)))) }.getOrNull() as? Map<*, *>
        return (spec?.get("dependencies") as? Map<*, *>)?.keys?.mapTo(HashSet()) { it.toString() }.orEmpty()
    }
}
