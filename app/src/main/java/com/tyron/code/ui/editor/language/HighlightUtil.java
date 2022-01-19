package com.tyron.code.ui.editor.language;

import com.tyron.builder.model.DiagnosticWrapper;

import org.openjdk.javax.tools.Diagnostic;

import java.util.List;

import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Indexer;
import io.github.rosemoe.sora.text.TextAnalyzeResult;
import io.github.rosemoe.sora.widget.CodeEditor;
import io.github.rosemoe.sora.widget.EditorColorScheme;

public class HighlightUtil {

    /**
     * Highlights the list of given diagnostics, taking care of conversion between 1-based offsets
     * to 0-based offsets.
     * It also makes the Diagnostic eligible for shifting as the user types.
     */
    public static void markDiagnostics(CodeEditor editor, List<DiagnosticWrapper> diagnostics,
                                 TextAnalyzeResult colors) {
        editor.getText().beginStreamCharGetting(0);
        Indexer indexer = editor.getText().getIndexer();

        diagnostics.forEach(it -> {
            try {
                int startLine;
                int startColumn;
                int endLine;
                int endColumn;
                if (it.getPosition() != DiagnosticWrapper.USE_LINE_POS) {
                    if (it.getStartPosition() == -1) {
                        it.setStartPosition(it.getPosition());
                    }
                    if (it.getEndPosition() == -1) {
                        it.setEndPosition(it.getPosition());
                    }
                    CharPosition start = indexer.getCharPosition((int) it.getStartPosition());
                    CharPosition end = indexer.getCharPosition((int) it.getEndPosition());

                    // the editor does not support marking underline spans for the same start and end
                    // index
                    // to work around this, we just subtract one to the start index
                    if (start.line == end.line && end.column == start.column) {
                        start.column--;
                    }

                    it.setStartLine(start.line);
                    it.setEndLine(end.line);
                    it.setStartColumn(start.column);
                    it.setEndColumn(end.column);
                }
                startLine = it.getStartLine();
                startColumn = it.getStartColumn();
                endLine = it.getEndLine();
                endColumn = it.getEndColumn();

                int flag = it.getKind() == Diagnostic.Kind.ERROR ? Span.FLAG_ERROR :
                        Span.FLAG_WARNING;
                colors.markProblemRegion(flag, startLine, startColumn, endLine, endColumn);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                // ignored
            }
        });
        editor.getText().endStreamCharGetting();
    }

    public static int[] setErrorSpan(TextAnalyzeResult colors, int line, int column) {
        int lineCount = colors.getSpanMap().size();
        int realLine = line - 1;
        List<Span> spans = colors.getSpanMap().get(Math.min(realLine, lineCount - 1));

        int[] end = new int[2];
        end[0] = Math.min(realLine, lineCount - 1);

        if (realLine >= lineCount) {
            Span span = Span.obtain(0, EditorColorScheme.PROBLEM_ERROR);
            span.problemFlags = Span.FLAG_ERROR;
            colors.add(realLine, span);
            end[0]++;
        } else {
            Span last = null;
            for (int i = 0; i < spans.size(); i++) {
                Span span = spans.get(i);
                if (last != null) {
                    if (last.column <= column - 1 && span.column >= column - 1) {
                        span.problemFlags = Span.FLAG_ERROR;
                        last.problemFlags = Span.FLAG_ERROR;
                        end[1] = last.column;
                        break;
                    }
                }
                if (i == spans.size() - 1 && span.column <= column - 1) {
                    span.problemFlags = Span.FLAG_ERROR;
                    end[1] = span.column;
                    break;
                }
                last = span;
            }
        }
        return end;
    }

    /**
     * Used in xml diagnostics where line is only given
     */
    public static void setErrorSpan(TextAnalyzeResult colors, int line) {
        int lineCount = colors.getSpanMap().size();
        int realLine = line - 1;
        List<Span> spans = colors.getSpanMap().get(Math.min(realLine, lineCount - 1));

        for (Span span : spans) {
            span.problemFlags = Span.FLAG_ERROR;
        }
    }
}
