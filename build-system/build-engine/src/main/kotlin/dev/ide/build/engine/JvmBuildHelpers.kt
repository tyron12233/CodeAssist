package dev.ide.build.engine

import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ContentRole
import dev.ide.model.DependencyScope
import dev.ide.model.LanguageLevel
import dev.ide.model.Module
import dev.ide.model.SourceSet
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

/**
 * The conventional generated-source root of a module: `<module>/build/generated`, which is where
 * [dev.ide.build.BuildEnv.generatedDir] writes. It counts as a source root whether or not the module
 * declares one, so a contributed build plugin or source generator that emits there is compiled without the
 * project having to declare a root for output the build itself produced.
 */
fun generatedRoot(module: Module): Path =
    (outputDir(module).parent ?: outputDir(module)).resolve("generated")

/**
 * De-duplicate [roots] and drop any that sits inside another, keeping the outermost. A source-root list
 * assembled from several places (a module's declared roots, a pipeline's generated directory, the
 * conventional generated root) can otherwise present the same file twice, which a compiler rejects.
 */
fun collapseNestedRoots(roots: List<Path>): List<Path> {
    val all = roots.map { it.toAbsolutePath().normalize() }.distinct()
    return all.filterNot { root -> all.any { it != root && root.startsWith(it) } }
}

/**
 * A module's source root directories: its declared `SOURCE` and `GENERATED` roots plus [generatedRoot],
 * keeping only those that exist on disk.
 */
fun sourceRootDirs(module: Module): List<Path> {
    val declared = module.sourceSets
        .filter { producesOutput(it) }
        .flatMap { it.contentRoots }
        .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
        .map { Paths.get(it.dir.path) }
    return collapseNestedRoots(declared.plusElement(generatedRoot(module))).filter { Files.isDirectory(it) }
}

/**
 * Does [sourceSet] feed the module's output?
 *
 * A CodeAssist module is a single compilation, so this is what separates the sources that compile into it
 * from a **test-only** source set (`src/test`, `commonTest`), which does not: its scope puts it on neither
 * the compile nor the runtime classpath, and so do the dependencies written for it. Compiling it into the
 * main output would fail every time it touches a test dependency — the assertion library it imports is
 * declared `testImplementation`, which is precisely a scope the compile classpath does not carry — while
 * appearing to be an ordinary unresolved reference in the user's code.
 *
 * This is not "tests are skipped": there is no test compilation to run them in yet (BuildGoal.TEST has no
 * task behind it). When there is, it will ask for exactly the source sets excluded here, against the
 * test-scoped classpath they were written for.
 */
private fun producesOutput(sourceSet: SourceSet): Boolean =
    sourceSet.scope.onCompile || sourceSet.scope.onRuntime

fun sourceFiles(module: Module): List<Path> = sourceRootDirs(module)
    .flatMap { root -> Files.walk(root).use { s -> s.filter { it.toString().endsWith(".java") }.collect(Collectors.toList()) } }

fun depOutputDirs(module: Module): List<Path> =
    module.classpath(DependencyScope.IMPLEMENTATION).entries
        .filter { it.kind == ClasspathEntryKind.MODULE_OUTPUT }.map { Paths.get(it.root.path) }

fun libJars(module: Module): List<Path> =
    module.classpath(DependencyScope.IMPLEMENTATION).entries
        .filter { it.kind == ClasspathEntryKind.LIBRARY }.map { Paths.get(it.root.path) }

/** The module's own directory. */
fun moduleDir(module: Module): Path = Paths.get(module.dir.path)

/** The module's build directory (`<moduleDir>/build`): generated sources, intermediates, outputs. */
/**
 * The module's build directory: everything the build writes lives under it.
 *
 * Derived from the compile output rather than fixed at `<module>/build`, the same way [generatedRoot] is, so
 * that a module which moves its output moves its whole build tree with it. That is what lets a project built
 * by two build systems keep them apart (see [dev.ide.model.ModifiableModule.outputRelPath]); a module that
 * leaves the output at the default `build/classes` still gets `<module>/build`, as before.
 */
fun buildDir(module: Module): Path =
    module.outputDir?.let { Paths.get(it.path).parent } ?: moduleDir(module).resolve("build")

/**
 * A module's compile output directory. Required rather than optional: these helpers exist for the JVM build,
 * and a module that declares no compiled output is not one it can build.
 */
fun outputDir(module: Module): Path = Paths.get(
    requireNotNull(module.outputDir) { "module '${module.name}' has no compiled output directory" }.path,
)

/** Convention path of a module's `jar` artifact (`build/libs/<name>.jar`, Gradle-style) - shared so other
 *  plugins (Android) can consume it by the same path. */
fun jarPath(module: Module): Path =
    outputDir(module).resolveSibling("libs").resolve("${module.name}.jar")

/** javac's `-source`/`-target` argument. A level that names no Java version compiles at the default. */
fun levelOf(level: LanguageLevel): String = level.javaVersion.toString()

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
