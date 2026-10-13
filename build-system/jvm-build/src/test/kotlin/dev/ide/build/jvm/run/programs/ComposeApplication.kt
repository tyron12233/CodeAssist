package dev.ide.build.jvm.run.programs

import androidx.compose.ui.window.application

/** A Compose for Desktop `main` in miniature: `application { }` with its default `exitProcessOnExit`. */
fun main() {
    application { _, _, _ -> println("composed") }
}
