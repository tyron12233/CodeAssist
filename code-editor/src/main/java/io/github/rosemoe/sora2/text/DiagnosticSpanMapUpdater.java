package io.github.rosemoe.sora2.text;

import com.tyron.builder.model.DiagnosticWrapper;

import java.util.List;

public class DiagnosticSpanMapUpdater {

    public static void shiftDiagnosticsOnSingleLineInsert(List<DiagnosticWrapper> diagnostics, int line, int startCol, int endCol) {
        int length = endCol - startCol;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (diagnostic.getStartLine() < line) {
                continue;
            }
            if (diagnostic.getStartLine() == line) {
                if (diagnostic.getStartColumn() < startCol) {
                    continue;
                }
            }
            diagnostic.setStartPosition(diagnostic.getStartPosition() + length);
            diagnostic.setEndPosition(diagnostic.getEndPosition() + length);
        }
    }

    public static void shiftDiagnosticsOnSingleLineDelete(List<DiagnosticWrapper> diagnostics, int line, int startCol, int endCol) {
        int length = endCol - startCol;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (diagnostic.getStartLine() < line) {
                continue;
            }
            if (diagnostic.getStartLine() == line)  {
                if (diagnostic.getStartColumn() < startCol) {
                    continue;
                }
            }
            diagnostic.setStartPosition(diagnostic.getStartPosition() - length);
            diagnostic.setEndPosition(diagnostic.getEndPosition() - length);
        }
    }

    public static void shiftDiagnosticsOnMultiLineDelete(List<DiagnosticWrapper> diagnostics, int startLine, int startColumn, int endLine, int endColumn) {
        int length = endLine - startLine;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (diagnostic.getStartLine() < startLine) {
                continue;
            }
            if (diagnostic.getEndLine() < endLine) {
                continue;
            }
            int dLength = diagnostic.getEndColumn() - diagnostic.getStartColumn();
            diagnostic.setStartLine(diagnostic.getStartLine() - length);
            diagnostic.setEndLine(diagnostic.getEndLine() - length);
            diagnostic.setPosition(DiagnosticWrapper.USE_LINE_POS);
        }
    }

    public static void shiftDiagnosticsOnMultiLineInsert(List<DiagnosticWrapper> diagnostics, int startLine, int startColumn, int endLine, int endColumn) {
        int length = endLine - startLine;
        for (DiagnosticWrapper diagnostic : diagnostics) {
            if (diagnostic.getStartLine() < startLine) {
                continue;
            }
            if (diagnostic.getStartLine() == startLine) {
                if (diagnostic.getStartColumn() <= startColumn) {
                    continue;
                }
                if (diagnostic.getEndColumn() <= endColumn) {
                    continue;
                }
            }
            diagnostic.setStartLine(diagnostic.getStartLine() + length);
            diagnostic.setEndLine(diagnostic.getEndLine() + length);
            diagnostic.setPosition(DiagnosticWrapper.USE_LINE_POS);
        }
    }
}
