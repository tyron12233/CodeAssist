package com.tyron.code.language;

import android.util.Log;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.completion.progress.ProgressManager;
import com.tyron.editor.CharPosition;
import com.tyron.editor.Editor;

import javax.tools.Diagnostic;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Spans;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora2.BuildConfig;

public class HighlightUtil {

    public static void replaceSpan(Styles styles, Span newSpan, int startLine, int startColumn, int endLine, int endColumn) {
        for (int line = startLine; line <= endLine; line++) {
            ProgressManager.checkCanceled();
            int start = (line == startLine ? startColumn : 0);
            int end = (line == endLine ? endColumn : Integer.MAX_VALUE);
            Spans.Reader read = styles.getSpans().read();
            List<io.github.rosemoe.sora.lang.styling.Span> spans = new ArrayList<>(read.getSpansOnLine(line));
            int increment;
            for (int i = 0; i < spans.size(); i += increment) {
                ProgressManager.checkCanceled();
                io.github.rosemoe.sora.lang.styling.Span span = spans.get(i);
                increment = 1;
                if (span.column >= end) {
                    break;
                }
                int spanEnd = (i + 1 >= spans.size() ? Integer.MAX_VALUE : spans.get(i + 1).column);
                if (spanEnd >= start) {
                    int regionStartInSpan = Math.max(span.column, start);
                    int regionEndInSpan = Math.min(end, spanEnd);
                    if (regionStartInSpan == span.column) {
                        if (regionEndInSpan != spanEnd) {
                            increment = 2;
                            io.github.rosemoe.sora.lang.styling.Span nSpan = span.copy();
                            nSpan.column = regionEndInSpan;
                            spans.add(i + 1, nSpan);
                        }
                        span.problemFlags = newSpan.problemFlags;
                        span.underlineColor = newSpan.underlineColor;
                        span.style = newSpan.style;
                        span.renderer = newSpan.renderer;
                    } else {
                        //regionStartInSpan > span.column
                        if (regionEndInSpan == spanEnd - 1) {
                            increment = 2;
                            io.github.rosemoe.sora.lang.styling.Span nSpan = span.copy();
                            nSpan.column = regionStartInSpan;
                            spans.add(i + 1, nSpan);
                            span.problemFlags = newSpan.problemFlags;
                            span.underlineColor = newSpan.underlineColor;
                            span.style = newSpan.style;
                            span.renderer = newSpan.renderer;
                        } else {
                            increment = 3;
                            io.github.rosemoe.sora.lang.styling.Span span1 = span.copy();
                            span1.column = regionStartInSpan;
                            span1.problemFlags = newSpan.problemFlags;
                            span1.underlineColor = newSpan.underlineColor;
                            span1.style = newSpan.style;
                            span1.renderer = newSpan.renderer;
                            io.github.rosemoe.sora.lang.styling.Span span2 = span.copy();
                            span2.column = regionEndInSpan;
                            spans.add(i + 1, span1);
                            spans.add(i + 2, span2);
                        }
                    }
                }
            }

            Spans.Modifier modify = styles.getSpans().modify();
            modify.setSpansOnLine(line, spans);
        }
    }

    public static void markProblemRegion(Styles styles, int newFlag, int startLine, int startColumn, int endLine, int endColumn) {
        for (int line = startLine; line <= endLine; line++) {
            ProgressManager.checkCanceled();
            int start = (line == startLine ? startColumn : 0);
            int end = (line == endLine ? endColumn : Integer.MAX_VALUE);
            Spans.Reader read = styles.getSpans().read();
            List<io.github.rosemoe.sora.lang.styling.Span> spans = new ArrayList<>(read.getSpansOnLine(line));
            int increment;
            for (int i = 0; i < spans.size(); i += increment) {
                ProgressManager.checkCanceled();
                io.github.rosemoe.sora.lang.styling.Span span = spans.get(i);
                increment = 1;
                if (span.column >= end) {
                    break;
                }
                int spanEnd = (i + 1 >= spans.size() ? Integer.MAX_VALUE : spans.get(i + 1).column);
                if (spanEnd >= start) {
                    int regionStartInSpan = Math.max(span.column, start);
                    int regionEndInSpan = Math.min(end, spanEnd);
                    if (regionStartInSpan == span.column) {
                        if (regionEndInSpan != spanEnd) {
                            increment = 2;
                            io.github.rosemoe.sora.lang.styling.Span nSpan = span.copy();
                            nSpan.column = regionEndInSpan;
                            spans.add(i + 1, nSpan);
                        }
                        span.problemFlags |= newFlag;
                    } else {
                        //regionStartInSpan > span.column
                        if (regionEndInSpan == spanEnd) {
                            increment = 2;
                            io.github.rosemoe.sora.lang.styling.Span nSpan = span.copy();
                            nSpan.column = regionStartInSpan;
                            spans.add(i + 1, nSpan);
                            nSpan.problemFlags |= newFlag;
                        } else {
                            increment = 3;
                            io.github.rosemoe.sora.lang.styling.Span span1 = span.copy();
                            span1.column = regionStartInSpan;
                            span1.problemFlags |= newFlag;
                            io.github.rosemoe.sora.lang.styling.Span span2 = span.copy();
                            span2.column = regionEndInSpan;
                            spans.add(i + 1, span1);
                            spans.add(i + 2, span2);
                        }
                    }
                }
            }

            Spans.Modifier modify = styles.getSpans().modify();
            modify.setSpansOnLine(line, spans);
        }
    }


    /**
     * Highlights the list of given diagnostics, taking care of conversion between 1-based offsets
     * to 0-based offsets.
     * It also makes the Diagnostic eligible for shifting as the user types.
     */
    public static void markDiagnostics(Editor editor, List<DiagnosticWrapper> diagnostics,
                                       Styles styles) {
        diagnostics.forEach(it -> {
            ProgressManager.checkCanceled();
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
                    CharPosition start = editor.getCharPosition((int) it.getStartPosition());
                    CharPosition end = editor.getCharPosition((int) it.getEndPosition());

                    int sLine = start.getLine();
                    int sColumn = start.getColumn();
                    int eLine = end.getLine();
                    int eColumn = end.getColumn();

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
                markProblemRegion(styles, flag, startLine, startColumn, endLine, endColumn);
            } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
                if (BuildConfig.DEBUG) {
                    Log.d("HighlightUtil", "Failed to mark diagnostics", e);
                }
            }
        });
    }

    /**
     * Used in xml diagnostics where line is only given
     */
    public static void setErrorSpan(Styles colors, int line) {
        try {
            Spans.Reader reader = colors.getSpans().read();
            int realLine = line - 1;
            List<io.github.rosemoe.sora.lang.styling.Span> spans = reader.getSpansOnLine(realLine);

            for (io.github.rosemoe.sora.lang.styling.Span span : spans) {
                span.problemFlags = Span.FLAG_ERROR;
            }
        } catch (IndexOutOfBoundsException e) {
            // ignored
        }
    }

    public static void clearDiagnostics(Styles styles) {
        Spans spans = styles.getSpans();
        Spans.Reader read = spans.read();
        for (int i = 0; i < spans.getLineCount(); i++) {
            List<Span> spansOnLine = new ArrayList<>(read.getSpansOnLine(i));
            for (Span span : spansOnLine) {
                span.problemFlags = 0;
            }
            spans.modify().setSpansOnLine(i, spansOnLine);
        }
    }
}
