package dev.ide.core

import dev.ide.core.build.BuildFailureKind
import dev.ide.ui.backend.BuildDiagnosticUi
import dev.ide.ui.backend.BuildLogLine
import dev.ide.ui.backend.UiLogLevel
import dev.ide.ui.backend.UiSeverity
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildFailureKindTest {
    private fun diag(sev: UiSeverity, kind: String, msg: String = "", detail: String? = null) =
        BuildDiagnosticUi(severity = sev, message = msg, kind = kind, detail = detail)

    private fun line(level: UiLogLevel, msg: String) = BuildLogLine(message = msg, level = level)

    @Test fun compileErrorIsUserCode() = assertEquals(
        "compile",
        BuildFailureKind.classify(listOf(diag(UiSeverity.Error, "compiler", "cannot find symbol")), emptyList()),
    )

    @Test fun resourceError() = assertEquals(
        "resource",
        BuildFailureKind.classify(listOf(diag(UiSeverity.Error, "resource", "aapt2 error")), emptyList()),
    )

    @Test fun compilerBeatsDexWhenBothPresent() = assertEquals(
        "compile",
        BuildFailureKind.classify(
            listOf(diag(UiSeverity.Error, "dex"), diag(UiSeverity.Error, "compiler")), emptyList(),
        ),
    )

    @Test fun dexFailureIsToolBucket() = assertEquals(
        "tool",
        BuildFailureKind.classify(listOf(diag(UiSeverity.Error, "dex", "D8 failed")), emptyList()),
    )

    @Test fun r8FactsComeFromTheShrinkersNotes() {
        val facts = dev.ide.core.build.R8BuildFacts.props(listOf(
            line(UiLogLevel.Info, "R8: forked VM, 768MB heap (vs the 256MB app limit)"),
            line(UiLogLevel.Warn, ":app:minifyReleaseWithR8: R8 ran out of memory shrinking the whole app (~40MB of classes). Retrying"),
        ))
        assertEquals(mapOf("r8_mode" to "forked", "r8_heap_mb" to "768", "r8_fallback" to "true"), facts)
        assertEquals(
            mapOf("r8_mode" to "in-process"),
            dev.ide.core.build.R8BuildFacts.props(listOf(line(UiLogLevel.Info, "R8: in-process (no forked-VM launcher on this device)"))),
        )
        assertEquals(emptyMap(), dev.ide.core.build.R8BuildFacts.props(listOf(line(UiLogLevel.Info, "compileKotlin done"))))
    }

    @Test fun rewrittenToolOutOfMemoryIsStillOom() = assertEquals(
        "oom",
        BuildFailureKind.classify(
            listOf(diag(UiSeverity.Error, "dex", "The dexer ran out of memory. R8 ran with a 768MB heap.")), emptyList(),
        ),
    )

    @Test fun oomBeatsEverythingFromDiagnosticAndLog() {
        assertEquals(
            "oom",
            BuildFailureKind.classify(listOf(diag(UiSeverity.Error, "compiler", "java.lang.OutOfMemoryError")), emptyList()),
        )
        assertEquals(
            "oom",
            BuildFailureKind.classify(emptyList(), listOf(line(UiLogLevel.Error, "java.lang.OutOfMemoryError: Java heap space"))),
        )
    }

    @Test fun failedWithNoErrorDiagnosticIsOurBug() = assertEquals(
        BuildFailureKind.NO_DIAGNOSTIC,
        BuildFailureKind.classify(
            listOf(diag(UiSeverity.Warning, "compiler", "unchecked")), // warnings don't count
            listOf(line(UiLogLevel.Info, "> Task :compileJava")),
        ),
    )
}
