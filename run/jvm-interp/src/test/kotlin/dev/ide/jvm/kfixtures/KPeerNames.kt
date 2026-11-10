package dev.ide.jvm.kfixtures

import dev.ide.jvm.host.Shape

/** A backtick-named function, as Kotlin tests use: the anonymous object inside compiles to
 *  `KPeerNamesKt$sources are every file, sorted$1`, a JVM name with spaces and a comma. */
fun `sources are every file, sorted`(): Shape = object : Shape() {
    override fun sides(): Int = 4
}
