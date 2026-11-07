package dev.codeassist.dart

import dev.ide.model.BuildSystemId
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.FacetTemplate
import dev.ide.model.ModuleType
import dev.ide.model.PlatformKind
import dev.ide.model.SourceSetTemplate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * A Dart package as a module: its directory is the package (where `pubspec.yaml` is), and its content roots
 * are the directories Dart tools give a meaning to (`lib/`, `bin/`, `test/`, and `example/` and `tool/` when
 * they exist), under one role of this plugin's own. The Project view shows them with the pubspec beside
 * them, as Dart tools lay a package out, rather than as JVM source roots it does not have.
 */
object DartModuleType : ModuleType {
    const val ID = "dart-package"

    override val id = ID
    override val displayName = "Dart package"
    override val platform: PlatformKind get() = DART_PLATFORM
    override fun defaultSourceSets(): List<SourceSetTemplate> = listOf(
        SourceSetTemplate("main", DependencyScope.IMPLEMENTATION, mapOf("lib" to setOf(DART_SOURCE), "bin" to setOf(DART_SOURCE))),
        SourceSetTemplate("test", DependencyScope.TEST_IMPLEMENTATION, mapOf("test" to setOf(DART_SOURCE))),
    )
    override fun defaultFacets(): List<FacetTemplate> = emptyList()
    override fun supportedBuildSystems(): Set<BuildSystemId> = setOf(BuildSystemId.NATIVE, PUB)

    /** A directory of Dart code in a package. Not a Java source root: nothing JVM-shaped reads it. */
    val DART_SOURCE = ContentRole("dart-source")

    /** Dart compiles against its own SDK, never android.jar or a JDK. */
    val DART_PLATFORM = PlatformKind("DART")

    /** The build system an imported pub package is read by ([PubImporter]). */
    val PUB = BuildSystemId("pub")
}

/** What the plugin needs to know about a package on disk. Pure file reads; nothing here runs Dart. */
object DartPackages {
    const val PUBSPEC = "pubspec.yaml"
    const val PACKAGE_CONFIG = ".dart_tool/package_config.json"

    /** The nearest directory at or above [file] holding a `pubspec.yaml`, stopping at [limit]. */
    fun packageRootOf(file: Path, limit: Path): Path? {
        var dir: Path? = if (Files.isDirectory(file)) file else file.parent
        while (dir != null && dir.startsWith(limit)) {
            if (Files.isRegularFile(dir.resolve(PUBSPEC))) return dir
            dir = dir.parent
        }
        return null
    }

    /** The package's `name:` from its pubspec text, or null when it has none. */
    fun nameOf(pubspec: String): String? =
        Regex("""(?m)^name:\s*['"]?([A-Za-z_][A-Za-z0-9_]*)['"]?\s*(?:#.*)?$""").find(pubspec)?.groupValues?.get(1)

    /**
     * A valid package name made from a free-form project name: lowercase words joined by underscores, as pub
     * requires (`My App` -> `my_app`), never starting with a digit.
     */
    fun packageName(raw: String): String {
        val words = Regex("[A-Za-z0-9]+").findAll(
            // Split camelCase too: `HelloWorld` -> `hello_world`.
            raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2"),
        ).map { it.value.lowercase() }.toList()
        val name = words.joinToString("_").ifEmpty { "app" }
        return if (name.first().isDigit()) "app_$name" else name
    }

    /**
     * Whether `pub get` has to run before [pkg] can be compiled or analysed: it never has, `pubspec.yaml` or
     * `pubspec.lock` changed since it last did, or a package the config names is no longer on disk. A package
     * importing itself (`package:name/...`) needs the package config even with no dependencies at all.
     */
    fun needsPubGet(pkg: Path): Boolean {
        val config = pkg.resolve(PACKAGE_CONFIG)
        if (!Files.isRegularFile(config)) return true
        val configAt = Files.getLastModifiedTime(config).toMillis()
        val changed = listOf(PUBSPEC, "pubspec.lock").any { name ->
            val f = pkg.resolve(name)
            Files.isRegularFile(f) && Files.getLastModifiedTime(f).toMillis() > configAt
        }
        // The config names packages by their folder in the pub cache. With that folder gone (the cache or the
        // plugin's data cleared) a newer config still compiled against nothing: thousands of errors inside
        // Flutter itself, its `meta` and `vector_math` missing.
        return changed || packages(pkg).any { (_, root) -> !Files.isDirectory(root) }
    }

    /** Package name to root directory, from `.dart_tool/package_config.json`. */
    fun packages(pkg: Path): List<Pair<String, Path>> {
        val configFile = pkg.resolve(PACKAGE_CONFIG)
        val text = runCatching { String(Files.readAllBytes(configFile)) }.getOrNull() ?: return emptyList()
        val config = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
        val base = configFile.parent.toUri()
        return (config["packages"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val rootUri = (o["rootUri"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val resolved = runCatching { Paths.get(base.resolve(URI(if (rootUri.endsWith("/")) rootUri else "$rootUri/"))) }
                .getOrNull() ?: return@mapNotNull null
            name to resolved.normalize()
        }
    }

    /**
     * The content roots of the package at [pkg], by source set: `lib/` and `test/` always (where a package's
     * code and tests go, so the Project view shows them as soon as they exist; it skips a root that does not),
     * the other conventional directories when they are there.
     */
    fun contentRoots(pkg: Path): Map<String, List<String>> {
        val main = listOf("lib") + listOf("bin", "example", "tool").filter { Files.isDirectory(pkg.resolve(it)) }
        return linkedMapOf("main" to main, "test" to listOf("test"))
    }

    /** The programs a package offers to run: the `.dart` files directly in `bin/`, as pub does, sorted. */
    fun executables(pkg: Path): List<Path> {
        val bin = pkg.resolve("bin")
        if (!Files.isDirectory(bin)) return emptyList()
        return Files.list(bin).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".dart") }.collect(Collectors.toList())
        }.sortedBy { it.fileName.toString() }
    }
}
