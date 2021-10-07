/*
 *    CodeEditor - the awesome code editor for Android
 *    Copyright (C) 2020-2021  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 */
package io.github.rosemoe.sora.text;

import java.util.ArrayList;
import java.util.List;

import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.widget.EditorColorScheme;

/**
 * Update spans on text change event
 *
 * @author Rose
 */
public class SpanMapUpdater {

    public static void shiftSpansOnMultiLineDelete(List<List<Span>> map, int startLine, int startColumn, int endLine, int endColumn) {
        int lineCount = endLine - startLine - 1;
        // Remove unrelated lines
        while (lineCount > 0) {
            Span.recycleAll(map.remove(startLine + 1));
            lineCount--;
        }
        // Clean up start line
        List<Span> startLineSpans = map.get(startLine);
        int index = startLineSpans.size() - 1;
        while (index > 0) {
            if (startLineSpans.get(index).column >= startColumn) {
                startLineSpans.remove(index).recycle();
                index--;
            } else {
                break;
            }
        }
        // Shift end line
        List<Span> endLineSpans = map.get(startLine + 1);
        while (endLineSpans.size() > 1) {
            Span first = endLineSpans.get(0);
            if (first.column >= endColumn) {
                break;
            } else {
                int spanEnd = endLineSpans.get(1).column;
                if (spanEnd <= endColumn) {
                    endLineSpans.remove(first);
                    first.recycle();
                } else {
                    break;
                }
            }
        }
        for (int i = 0; i < endLineSpans.size(); i++) {
            Span span = endLineSpans.get(i);
            if (span.column < endColumn) {
                span.column = 0;
            } else {
                span.column -= endColumn;
            }
        }
    }

    public static void shiftSpansOnSingleLineDelete(List<List<Span>> map, int line, int startCol, int endCol) {
        if (map == null || map.isEmpty()) {
            return;
        }
        List<Span> spanList = map.get(line);
        int startIndex = findSpanIndexFor(spanList, 0, startCol);
        if (startIndex == -1) {
            //No span is to be updated
            return;
        }
        int endIndex = findSpanIndexFor(spanList, startIndex, endCol);
        if (endIndex == -1) {
            endIndex = spanList.size();
        }
        // Remove spans inside delete text
        int removeCount = endIndex - startIndex;
        for (int i = 0; i < removeCount; i++) {
            spanList.remove(startIndex).recycle();
        }
        // Shift spans
        int delta = endCol - startCol;
        while (startIndex < spanList.size()) {
            spanList.get(startIndex).column -= delta;
            startIndex++;
        }
        // Ensure there is span
        if (spanList.isEmpty() || spanList.get(0).column != 0) {
            spanList.add(0, Span.obtain(0, EditorColorScheme.TEXT_NORMAL));
        }
        // Remove spans with length 0
        for (int i = 0; i + 1 < spanList.size(); i++) {
            if (spanList.get(i).column >= spanList.get(i + 1).column) {
                spanList.remove(i).recycle();
                i--;
            }
        }
    }

    public static void shiftSpansOnSingleLineInsert(List<List<Span>> map, int line, int startCol, int endCol) {
        if (map == null || map.isEmpty()) {
            return;
        }
        List<Span> spanList = map.get(line);
        int index = findSpanIndexFor(spanList, 0, startCol);
        if (index == -1) {
            return;
        }
        int originIndex = index;
        // Shift spans after insert position
        int delta = endCol - startCol;
        while (index < spanList.size()) {
            spanList.get(index++).column += delta;
        }
        // Add extra span for line start
        if (originIndex == 0) {
            Span first = spanList.get(0);
            if (first.colorId == EditorColorScheme.TEXT_NORMAL && first.underlineColor == 0) {
                first.column = 0;
            } else {
                spanList.add(0, Span.obtain(0, EditorColorScheme.TEXT_NORMAL));
            }
        }
    }

    public static void shiftSpansOnMultiLineInsert(List<List<Span>> map, int startLine, int startColumn, int endLine, int endColumn) {
        // Find extended span
        List<Span> startLineSpans = map.get(startLine);
        int extendedSpanIndex = findSpanIndexFor(startLineSpans, 0, startColumn);
        if (extendedSpanIndex == -1) {
            extendedSpanIndex = startLineSpans.size() - 1;
        }
        if (startLineSpans.get(extendedSpanIndex).column > startColumn) {
            extendedSpanIndex--;
        }
        Span extendedSpan;
        if (extendedSpanIndex < 0 || extendedSpanIndex >= startLineSpans.size()) {
            extendedSpan = Span.obtain(0, EditorColorScheme.TEXT_NORMAL);
        } else {
            extendedSpan = startLineSpans.get(extendedSpanIndex);
        }
        // Create map link for new lines
        for (int i = 0; i < endLine - startLine; i++) {
            List<Span> list = new ArrayList<>();
            list.add(extendedSpan.copy().setColumn(0));
            map.add(startLine + 1, list);
        }
        // Add original spans to new line
        List<Span> endLineSpans = map.get(endLine);
        if (endColumn == 0 && extendedSpanIndex + 1 < startLineSpans.size()) {
            endLineSpans.clear();
        }
        int delta = Integer.MIN_VALUE;
        while (extendedSpanIndex + 1 < startLineSpans.size()) {
            Span span = startLineSpans.remove(extendedSpanIndex + 1);
            if (delta == Integer.MIN_VALUE) {
                delta = span.column;
            }
            endLineSpans.add(span.setColumn(span.column - delta + endColumn));
        }
    }

    private static int findSpanIndexFor(List<Span> spans, int initialPosition, int targetCol) {
        for (int i = initialPosition; i < spans.size(); i++) {
            if (spans.get(i).column >= targetCol) {
                return i;
            }
        }
        return -1;
    }

}
