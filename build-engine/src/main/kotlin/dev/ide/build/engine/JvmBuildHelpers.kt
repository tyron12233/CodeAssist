package dev.ide.build.engine

import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.stream.Collectors

/**
 * Language-neutral helpers for the JVM build: a module's source/output paths, its compile classpath
 * entries, the language level, and jar writing. The build system + plugin (`:jvm-build`) and the Android
 * build (`:android-support`) assemble their task graphs on these; the compile tasks themselves live in the
 * language modules (lang-jdt's `JdtCompileTask`, lang-kotlin's `KotlinCompileTask`), so build-engine names
 * no compiler.
 */

fun sourceFiles(module: Module): List<Path> = module.sourceSets
    .flatMap { it.contentRoots }
    .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
    .map { Paths.get(it.dir.path) }
    .filter { Files.isDirectory(it) }
    .flatMap { root -> Files.walk(root).use { s -> s.filter { it.toString().endsWith(".java") }.collect(Collectors.toList()) } }

fun depOutputDirs(module: Module): List<Path> =
    module.classpath(DependencyScope.IMPLEMENTATION).entries
        .filter { it.kind == ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }

fun libJars(module: Module): List<Path> =
    module.classpath(DependencyScope.IMPLEMENTATION).entries
        .filter { it.kind == ClasspathEntryKind.LIBRARY }.map { Paths.get(it.root.path) }

fun outputDir(module: Module): Path = Paths.get(module.outputDir.path)

/** Convention path of a module's `jar` artifact (`build/libs/<name>.jar`, Gradle-style) — shared so other
 *  plugins (Android) can consume it by the same path. */
fun jarPath(module: Module): Path =
    Paths.get(module.outputDir.path).resolveSibling("libs").resolve("${module.name}.jar")

fun levelOf(level: LanguageLevel): String = when (level) {
    LanguageLevel.JAVA_8 -> "8"
    LanguageLevel.JAVA_11 -> "11"
    LanguageLevel.JAVA_17 -> "17"
    LanguageLevel.JAVA_21 -> "21"
}

/** Jar [classesDir], optionally rewriting each entry's bytes via [transform] (entryName, bytes) — used by
 *  the `jar` lifecycle task. The default identity transform is plain jarring. [mainClass], when set, becomes
 *  the manifest's `Main-Class` so the jar runs standalone (`java -jar`). */
internal fun writeJar(classesDir: Path, jarPath: Path, mainClass: String? = null, transform: (String, ByteArray) -> ByteArray = { _, b -> b }) =
    writeJar(listOf(classesDir), jarPath, mainClass, transform)

/**
 * Jar one or more [classesDirs] (Java + Kotlin output) into [jarPath]. Later dirs win on a name clash; each
 * entry's bytes pass through [transform]. Directory entries and duplicate names are dropped.
 *
 * A `META-INF/MANIFEST.MF` is ALWAYS written (as the archive's leading entry) — a jar with no manifest can't
 * be `java -jar`'d ("no main manifest attribute in <jar>") and an entry-less jar throws `ZipException: No
 * entries` on ART. [mainClass], when non-blank, is recorded as the manifest's `Main-Class` so the built jar is
 * directly runnable outside the app.
 */
internal fun writeJar(classesDirs: List<Path>, jarPath: Path, mainClass: String? = null, transform: (String, ByteArray) -> ByteArray = { _, b -> b }) {
    jarPath.parent?.let { Files.createDirectories(it) }
    val manifest = Manifest().apply {
        mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
        mainClass?.takeIf { it.isNotBlank() }?.let { mainAttributes[Attributes.Name.MAIN_CLASS] = it }
    }
    // The manifest is emitted by the JarOutputStream(out, manifest) constructor; guard against a second copy
    // arriving from a class dir (none normally do, but a stray one would be a duplicate-entry error).
    val seen = hashSetOf(JarFile.MANIFEST_NAME)
    JarOutputStream(Files.newOutputStream(jarPath), manifest).use { jos ->
        for (dir in classesDirs.filter { Files.isDirectory(it) }) {
            Files.walk(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.sorted().forEach { f ->
                    val name = dir.relativize(f).toString().replace('\\', '/')
                    if (!seen.add(name)) return@forEach
                    jos.putNextEntry(JarEntry(name))
                    jos.write(transform(name, Files.readAllBytes(f)))
                    jos.closeEntry()
                }
            }
        }
    }
}
