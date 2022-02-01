package com.tyron.code.ui.editor.language;

import android.util.Log;

import com.tyron.builder.model.DiagnosticWrapper;

import org.openjdk.javax.tools.Diagnostic;

import java.util.List;

import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Spans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora2.BuildConfig;
import io.github.rosemoe.sora2.data.Span;
import io.github.rosemoe.sora2.text.CharPosition;
import io.github.rosemoe.sora2.text.Indexer;
import io.github.rosemoe.sora2.text.TextAnalyzeResult;
import io.github.rosemoe.sora2.widget.CodeEditor;
import io.github.rosemoe.sora2.widget.EditorColorScheme;

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

                    int sLine = start.line;
                    int sColumn = start.column;
                    int eLine = end.line;
                    int eColumn = end.column;

                    // the editor does not support marking underline spans for the same start and end
                    // index
                    // to work around this, we just subtract one to the start index
                    if (sLine == eLine && eColumn == sColumn) {
                        sColumn--;
                        eColumn++;
                    }

                    it.setStartLine(sLine);
                    it.setEndLine(eLine);
                    it.setStartColumn(sColumn);
                    it.setEndColumn(eColumn);
                }
                startLine = it.getStartLine();
                startColumn = it.getStartColumn();
                endLine = it.getEndLine();
                endColumn = it.getEndColumn();

                int flag = it.getKind() == Diagnostic.Kind.ERROR ? Span.FLAG_ERROR :
                        Span.FLAG_WARNING;
                colors.markProblemRegion(flag, startLine, startColumn, endLine, endColumn);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                if (BuildConfig.DEBUG) {
                    Log.d("HighlightUtil", "Failed to mark diagnostics", e);
                }
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

    /**
     * Used in xml diagnostics where line is only given
     */
    public static void setErrorSpan(Styles colors, int line) {
        Spans.Reader reader = colors.getSpans().read();
        int lineCount = reader.getSpanCount();
        int realLine = line - 1;
        List<io.github.rosemoe.sora.lang.styling.Span> spans = reader.getSpansOnLine(Math.min(realLine, lineCount - 1));

        for (io.github.rosemoe.sora.lang.styling.Span span : spans) {
            span.problemFlags = Span.FLAG_ERROR;
        }
    }

}
