package dev.ide.lang.kotlin.symbols

/**
 * The bundled kotlin-stdlib jar, or null where this build has none.
 *
 * Kotlin's stdlib is an implicit dependency of every Kotlin file, so the symbol service folds it into the
 * classpath itself rather than trusting a project to have declared it. Where it comes FROM is per-platform:
 * on the JVM and on ART it is a jar bundled as a classpath resource and extracted on demand — never the host
 * runtime, whose code source on device is the app's dex, which nothing can open as a jar.
 */
internal expect fun bundledStdlibJarPath(): String?
