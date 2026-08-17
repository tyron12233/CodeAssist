package dev.ide.testkit

import dev.ide.lang.AnnotationProcessor
import dev.ide.lang.CompilationContext
import dev.ide.model.ClasspathEntry
import dev.ide.model.ClasspathEntryKind
import dev.ide.model.ClasspathSnapshot
import dev.ide.model.LanguageLevel
import dev.ide.platform.ContentHash
import dev.ide.vfs.VirtualFile
import java.nio.file.Path

private fun snapshotOf(paths: List<Path>, kind: ClasspathEntryKind): ClasspathSnapshot = object : ClasspathSnapshot {
    override val entries: List<ClasspathEntry> = paths.map { ClasspathEntry(DiskVirtualFile(it), kind) }
    override fun fingerprint(): ContentHash = ContentHash(paths.joinToString())
}

/** A [ClasspathSnapshot] of LIBRARY entries over [jars]. */
fun libraryClasspath(jars: List<Path>): ClasspathSnapshot = snapshotOf(jars, ClasspathEntryKind.LIBRARY)

/**
 * A minimal [CompilationContext] for analyzer/parser/completion tests. [sourceRoots] are the source dirs;
 * [libraries] the LIBRARY classpath (e.g. the kotlin-stdlib jar); [bootClasspath] the SDK boot classpath
 * (empty by default — pass `Path.of(System.getProperty("java.home"))` for the JDK jrt image the way the JDT
 * tests do, or an `android.jar`); [sourceAttachments] the libraries' `-sources.jar`s (real parameter names +
 * javadoc); [languageLevel] defaults to Java 17.
 */
fun compilationContext(
    sourceRoots: List<Path>,
    libraries: List<Path> = emptyList(),
    bootClasspath: List<Path> = emptyList(),
    sourceAttachments: List<Path> = emptyList(),
    languageLevel: LanguageLevel = LanguageLevel.JAVA_17,
    outputDir: Path? = null,
    processors: List<AnnotationProcessor> = emptyList(),
): CompilationContext {
    val srcRoots = sourceRoots
    val libs = libraries
    val boot = bootClasspath
    val attachments = sourceAttachments
    val level = languageLevel
    val out = outputDir ?: sourceRoots.first()
    val procs = processors
    return object : CompilationContext {
        override val sourceRoots: List<VirtualFile> = srcRoots.map { DiskVirtualFile(it) }
        override val classpath: ClasspathSnapshot = snapshotOf(libs, ClasspathEntryKind.LIBRARY)
        override val bootClasspath: ClasspathSnapshot = snapshotOf(boot, ClasspathEntryKind.SDK_BOOTCLASSPATH)
        override val languageLevel: LanguageLevel = level
        override val outputDir: VirtualFile = DiskVirtualFile(out)
        override val processors: List<AnnotationProcessor> = procs
        override val sourceAttachments: List<VirtualFile> = attachments.map { DiskVirtualFile(it) }
    }
}
