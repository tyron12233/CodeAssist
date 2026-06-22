package dev.ide.build.engine

import dev.ide.model.ContentRole
import dev.ide.model.Module
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors

/**
 * Language-neutral path helpers for the Kotlin build. The `compileKotlin` task itself lives in lang-kotlin
 * (`KotlinCompileTask`, driving K2 directly); build-engine only knows *where* a module's Kotlin sources and
 * output live, so the build system, the Java/Kotlin interop classpath wiring, and the dexer can find them.
 * Kotlin/Java interop: a Kotlin module's `.java` are resolution-only inputs to kotlinc (Kotlin may reference
 * same-module Java types), while the Java compiler sees the Kotlin output via the `kotlin-classes` dir.
 */

/** The `.kt` files in a module's SOURCE/GENERATED roots — kotlinc's program inputs. */
fun kotlinSourceFiles(module: Module): List<Path> = sourceRootDirs(module)
    .flatMap { root -> Files.walk(root).use { s -> s.filter { it.toString().endsWith(".kt") }.collect(Collectors.toList()) } }

/** True when a module carries any Kotlin source (so it needs a `compileKotlin` step). */
fun hasKotlinSources(module: Module): Boolean = sourceRootDirs(module)
    .any { root -> Files.walk(root).use { s -> s.anyMatch { it.toString().endsWith(".kt") } } }

private fun sourceRootDirs(module: Module): List<Path> = module.sourceSets
    .flatMap { it.contentRoots }
    .filter { ContentRole.SOURCE in it.roles || ContentRole.GENERATED in it.roles }
    .map { Paths.get(it.dir.path) }
    .filter { Files.isDirectory(it) }

/** Where a module's Kotlin `.class` output lands — a sibling of the Java [outputDir] so the two compilers
 *  never share a directory (which would make each see the other's classes as "changed" output every run). */
fun kotlinOutputDir(module: Module): Path = outputDir(module).resolveSibling("kotlin-classes")

/** For each upstream Java output dir, the sibling `kotlin-classes` dir an upstream Kotlin module emits into —
 *  added to a depender's compile classpath so it can reference an upstream module's Kotlin types. */
fun kotlinSiblings(javaOutputDirs: List<Path>): List<Path> =
    javaOutputDirs.map { it.resolveSibling("kotlin-classes") }
