package com.tyron.code.ui.editor.language;

import com.tyron.builder.model.DiagnosticWrapper;

import java.util.List;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.Indexer;

public class DiagnosticSpanMapUpdater {

    public static void shiftDiagnosticsOnSingleLineInsert(List<DiagnosticWrapper> diagnostics,
                                                          ContentReference ref,
                                                          CharPosition start,
                                                          CharPosition end) {
        Content reference = ref.getReference();
        Indexer indexer = reference.getIndexer();
        int length = end.index - start.index;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (!isValid(diagnostic)) {
                continue;
            }
            CharPosition startPosition =
                    indexer.getCharPosition((int) diagnostic.getStartPosition());
            CharPosition endPosition = indexer.getCharPosition((int) diagnostic.getEndPosition());

            // diagnostic is located before the insertion index, its not included
            if (endPosition.index <= end.index) {
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
        Content reference = ref.getReference();
        Indexer indexer = reference.getIndexer();
        int length = end.index - start.index;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (!isValid(diagnostic)) {
                continue;
            }
            CharPosition startPosition =
                    indexer.getCharPosition((int) diagnostic.getStartPosition());
            CharPosition endPosition = indexer.getCharPosition((int) diagnostic.getEndPosition());
            if (startPosition.index > start.index) {
                diagnostic.setStartPosition(diagnostic.getStartPosition() - length);
            }
            if (endPosition.index > end.index) {
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
