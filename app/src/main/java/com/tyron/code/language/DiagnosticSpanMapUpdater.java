package com.tyron.code.language;

import com.tyron.builder.model.DiagnosticWrapper;

import java.util.List;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;

public class DiagnosticSpanMapUpdater {

    public static void shiftDiagnosticsOnSingleLineInsert(List<DiagnosticWrapper> diagnostics,
                                                          ContentReference ref,
                                                          CharPosition start,
                                                          CharPosition end) {
        int length = end.index - start.index;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (!isValid(diagnostic)) {
                continue;
            }
            // diagnostic is located before the insertion index, its not included
            if (diagnostic.getEndPosition() <= end.index) {
                continue;
            }
            diagnostic.setStartPosition(diagnostic.getStartPosition() + length);
            diagnostic.setEndPosition(diagnostic.getEndPosition() + length);
        }
    }

    public static void shiftDiagnosticsOnSingleLineDelete(List<DiagnosticWrapper> diagnostics,
                                                          ContentReference ref,
                                                          CharPosition start,
                                                          CharPosition end) {
        int length = end.index - start.index;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (!isValid(diagnostic)) {
                continue;
            }
            if (diagnostic.getStartPosition() > start.index) {
                diagnostic.setStartPosition(diagnostic.getStartPosition() - length);
            }
            if (diagnostic.getEndPosition() > end.index) {
                diagnostic.setEndPosition(diagnostic.getEndPosition() - length);
            }
        }
    }

    public static void shiftDiagnosticsOnMultiLineDelete(List<DiagnosticWrapper> diagnostics,
                                                         ContentReference ref,
                                                         CharPosition start,
                                                         CharPosition end) {
        int length = end.index - start.index;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (!isValid(diagnostic)) {
                continue;
            }
            if (diagnostic.getStartPosition() < end.index) {
                continue;
            }
            diagnostic.setStartPosition(diagnostic.getStartPosition() - length);
            diagnostic.setEndPosition(diagnostic.getEndPosition() - length);
        }
    }

    public static void shiftDiagnosticsOnMultiLineInsert(List<DiagnosticWrapper> diagnostics,
                                                         ContentReference ref,
                                                         CharPosition start,
                                                         CharPosition end) {
        int length = end.index - start.index;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (!isValid(diagnostic)) {
                continue;
            }
            if (diagnostic.getEndPosition() < end.index) {
                continue;
            }
            diagnostic.setStartPosition(diagnostic.getStartPosition() + length);
            diagnostic.setEndPosition(diagnostic.getEndPosition() + length);
        }
    }

    public static boolean isValid(DiagnosticWrapper d) {
        return d.getStartPosition() >= 0 && d.getEndPosition() >= 0;
    }
}
