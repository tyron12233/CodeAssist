package dev.ide.ksp

import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guards the KSP crash-diagnostic rendering ([kspCrashTraceLines]). The real bug it addresses: on ART a KSP
 * crash surfaced as only "IllegalStateException: <class>" with no frames — because the old diagnostic logged
 * one newline-joined string (the console shows only its first line) AND only the leaf exception's own trace,
 * which can be empty. The fix walks the whole cause chain and emits one line per frame.
 */
class KspCrashTraceLinesTest {

    @Test
    fun surfacesIntermediateFramesWhenLeafHasNoTrace() {
        // Mirror the ART shape: InvocationTargetException (reflective invoke) -> an AA exception WITH frames ->
        // a leaf IllegalStateException carrying just a class name and NO stack trace of its own.
        val leaf = IllegalStateException("com.github.benmanes.caffeine.cache.SSMS").apply { stackTrace = emptyArray() }
        val mid = RuntimeException("reading class", leaf).apply {
            stackTrace = arrayOf(
                StackTraceElement("org.jetbrains.kotlin.analysis.api.Reader", "readClass", "Reader.kt", 42),
                StackTraceElement("com.google.devtools.ksp.impl.ResolverAAImpl", "getClassDeclarationByName", "ResolverAAImpl.kt", 7),
            )
        }
        val ite = InvocationTargetException(mid)

        val lines = kspCrashTraceLines(ite, 60)
        val text = lines.joinToString("\n")

        // Every frame is its own line (survives a one-line-per-log console).
        assertTrue(lines.none { it.contains("\n") }, "each line must be single-line:\n$text")
        // The leaf's class-name message is present and flagged as trace-less.
        assertTrue(lines.any { it.contains("com.github.benmanes.caffeine.cache.SSMS") }, "missing leaf message:\n$text")
        assertTrue(lines.any { it.contains("<no stack trace on this exception>") }, "missing trace-less marker:\n$text")
        // The USEFUL frames (on the intermediate cause) survive — this is the throw site the old code dropped.
        assertTrue(lines.any { it.contains("org.jetbrains.kotlin.analysis.api.Reader.readClass") }, "missing AA frame:\n$text")
        assertTrue(lines.any { it.startsWith("Caused by: ") }, "missing chain header:\n$text")
    }

    @Test
    fun respectsTheLineCapAndGuardsCycles() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        // Introduce a cause cycle: a -> b -> a. Must terminate.
        a.initCause(b)
        val lines = kspCrashTraceLines(b, 5)
        assertTrue(lines.size <= 5, "cap not respected: ${lines.size}")
    }
}
