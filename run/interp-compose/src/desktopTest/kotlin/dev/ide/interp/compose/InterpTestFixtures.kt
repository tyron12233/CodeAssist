package dev.ide.interp.compose

import dev.ide.lang.kotlin.symbols.KotlinSymbolService
import dev.ide.vfs.VirtualFile
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The classpath every preview fixture in this source set lowers against, and the per-jar scan cache they
 * share.
 *
 * A [KotlinSymbolService] scans each Kotlin jar on its classpath once for the extensions and top-level
 * callables that can't be found class-by-class (`listOf`, `Modifier.fillMaxSize`, …). The desktop test
 * classpath is the whole Compose stack (runtime/ui/foundation/material3 + stdlib), and a fixture that builds
 * a service per test case re-pays that scan per case. The scan result is content-keyed per jar and
 * cacheable, so pointing every fixture at one cache directory turns it into a once-per-classpath cost
 * shared by every test class in the run — and, since the cache lives under `build/`, by later runs too.
 */
object InterpTestClasspath {

    /** The jars on the desktop test runtime classpath. */
    val jars: List<Path> by lazy {
        System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .filter { it.endsWith(".jar") }
            .map { Paths.get(it) }
    }

    /** Where the per-jar scan results are persisted. The test task's working dir is the module dir. */
    val scanCache: Path =
        Paths.get(System.getProperty("interp.test.scanCache") ?: "build/tmp/classpath-scan-cache")
}

/** A [KotlinSymbolService] over [sourceRoots] and the test classpath, sharing the per-jar scan cache. */
fun previewSymbolService(sourceRoots: List<VirtualFile> = emptyList()): KotlinSymbolService =
    KotlinSymbolService(
        sourceRoots = sourceRoots,
        classpathJars = InterpTestClasspath.jars,
        cacheDir = InterpTestClasspath.scanCache,
    )
