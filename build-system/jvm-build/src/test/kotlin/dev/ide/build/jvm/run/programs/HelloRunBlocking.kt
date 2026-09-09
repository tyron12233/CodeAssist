package dev.ide.build.jvm.run.programs

import kotlinx.coroutines.runBlocking

/**
 * The reported program: `runBlocking { println(...) }`, compiled by Gradle against the same
 * kotlinx-coroutines the IDE ships, then interpreted by a console run.
 *
 * It is a real compile rather than a hand-written call because the point is the CALL SITE the Kotlin compiler
 * emits: against kotlinx-coroutines 1.11.0 that is `BuildersKt.runBlockingK$default`, a name the 1.10.2 the
 * IDE used to ship does not have at all (see `CoroutineRunTest`).
 */
fun main() {
    runBlocking { println("Hello World") }
}
