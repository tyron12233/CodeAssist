package dev.ide.build.jvm.run.programs

import kotlin.system.exitProcess

/** Ends with Kotlin's `exitProcess`, which the run must turn into its exit code rather than the IDE's. */
fun main() {
    println("before exit")
    exitProcess(7)
}
