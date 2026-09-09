package dev.ide.core

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
