package dev.ide.ios

/**
 * Two real entries out of `kotlin-stdlib` 2.4.0, in a real zip: `kotlin/annotation/annotation.kotlin_builtins`
 * and `kotlin/io/ConsoleKt.class`.
 *
 * Not a synthesised archive. The point of the classpath is that the editor reads what a repository actually
 * serves, and a hand-built fixture proves nothing about `@kotlin.Metadata` or `.kotlin_builtins` as the
 * Kotlin compiler emits them. These two are the smallest pair that is recognisable: the fragment carries the
 * built-in ENUMS (`AnnotationTarget`), and `ConsoleKt` carries `println`.
 *
 * Standing in for the download so the suite never touches the network — it is written straight into the
 * resolver's cache at the path the resolver would have written it to.
 */
internal object StdlibFixture {

    const val JAR_BASE64: String =
        "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" +
        "AAACAAIAoQAAAMEJAAAAAA=="
}
