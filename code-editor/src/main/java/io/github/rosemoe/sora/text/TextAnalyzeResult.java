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

import io.github.rosemoe.sora.data.BlockLine;
import io.github.rosemoe.sora.data.NavigationItem;
import io.github.rosemoe.sora.data.ObjectAllocator;
import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.widget.EditorColorScheme;

/**
 * The result of analysis
 */
public class TextAnalyzeResult {

    protected final List<BlockLine> mBlocks;
    protected final List<List<Span>> mSpanMap;
    protected Object mExtra;
    protected List<NavigationItem> mLabels;
    protected Span mLast;
    protected int mSuppressSwitch = Integer.MAX_VALUE;
    boolean determined = false;

    /**
     * Create a new result
     */
    public TextAnalyzeResult() {
        mLast = null;
        mSpanMap = new ArrayList<>(2048);
        mBlocks = new ArrayList<>(1024);
    }

    /**
     * Add a new span if required (colorId is different from last)
     *
     * @param spanLine Line
     * @param column   Column
     * @param colorId  Type
     */
    public void addIfNeeded(int spanLine, int column, int colorId) {
        if (mLast != null && mLast.colorId == colorId) {
            return;
        }
        add(spanLine, Span.obtain(column, colorId));
    }

    /**
     * Add a span directly
     * Note: the line should always >= the line of span last committed
     * if two spans are on the same line, you must add them in order by their column
     *
     * @param spanLine The line position of span
     * @param span     The span
     */
    public void add(int spanLine, Span span) {
        int mapLine = mSpanMap.size() - 1;
        if (spanLine == mapLine) {
            mSpanMap.get(spanLine).add(span);
        } else if (spanLine > mapLine) {
            Span extendedSpan = mLast;
            if (extendedSpan == null) {
                extendedSpan = Span.obtain(0, EditorColorScheme.TEXT_NORMAL);
            }
            while (mapLine < spanLine) {
                List<Span> lineSpans = new ArrayList<>();
                lineSpans.add(extendedSpan.copy().setColumn(0));
                mSpanMap.add(lineSpans);
                mapLine++;
            }
            List<Span> lineSpans = mSpanMap.get(spanLine);
            if (span.column == 0) {
                lineSpans.clear();
            }
            lineSpans.add(span);
        } else {
            throw new IllegalStateException("Invalid position");
        }
        mLast = span;
    }

    /**
     * This method must be called when whole text is analyzed
     *
     * @param line The line is the line last of text
     */
    public void determine(int line) {
        int mapLine = mSpanMap.size() - 1;
        Span extendedSpan = mLast;
        if (mLast == null) {
            extendedSpan = Span.obtain(0, EditorColorScheme.TEXT_NORMAL);
        }
        while (mapLine < line) {
            List<Span> lineSpans = new ArrayList<>();
            lineSpans.add(extendedSpan.copy().setColumn(0));
            mSpanMap.add(lineSpans);
            mapLine++;
        }
        determined = true;
    }

    /**
     * Get a new BlockLine object
     * <strong>It fields maybe not initialized with zero</strong>
     *
     * @return An idle BlockLine
     */
    public BlockLine obtainNewBlock() {
        return ObjectAllocator.obtainBlockLine();
    }

    /**
     * Add a new code block info
     *
     * @param block Info of code block
     */
    public void addBlockLine(BlockLine block) {
        mBlocks.add(block);
    }

    /**
     * Get list of code blocks
     *
     * @return code blocks
     */
    public List<BlockLine> getBlocks() {
        return mBlocks;
    }

    /**
     * Ensure the list not empty
     */
    public void addNormalIfNull() {
        if (mSpanMap.isEmpty()) {
            List<Span> spanList = new ArrayList<>();
            spanList.add(Span.obtain(0, EditorColorScheme.TEXT_NORMAL));
            mSpanMap.add(spanList);
        }
    }

    /**
     * Get code navigation list
     *
     * @return Current navigation list
     */
    public List<NavigationItem> getNavigation() {
        return mLabels;
    }

    /**
     * Set code navigation list
     *
     * @param navigation New navigation list
     */
    public void setNavigation(List<NavigationItem> navigation) {
        mLabels = navigation;
    }

    /**
     * Returns suppress switch
     *
     * @return suppress switch
     * @see TextAnalyzeResult#setSuppressSwitch(int)
     */
    public int getSuppressSwitch() {
        return mSuppressSwitch;
    }

    /**
     * Set suppress switch for editor
     * What is 'suppress switch' ?:
     * Suppress switch is a switch size for code block line drawing
     * and for the process to find out which code block the cursor is in.
     * Because the code blocks are not saved by the order of both start line and
     * end line,we are unable to know exactly when we should stop the process.
     * So without a suppress switch,it will cost a large of time to search code
     * blocks.
     * A suppress switch is the code block count in the first layer code block
     * (as well as its sub code blocks).
     * If you are unsure,do not set it.
     * The default value if Integer.MAX_VALUE
     *
     * @param suppressSwitch Suppress switch
     */
    public void setSuppressSwitch(int suppressSwitch) {
        mSuppressSwitch = suppressSwitch;
    }

    /**
     * Get span map
     */
    public List<List<Span>> getSpanMap() {
        return mSpanMap;
    }

    /**
     * Leave extra information for your language object
     */
    public void setExtra(Object extra) {
        mExtra = extra;
    }

    /**
     * Get extra information set by the text analyzer
     */
    public Object getExtra() {
        return mExtra;
    }

    /**
     * Marks a region with the given flag.
     * This can only be called after {@link TextAnalyzeResult#determine(int)} is called.
     */
    public void markProblemRegion(int newFlag, int startLine, int startColumn, int endLine, int endColumn) {
        if (!determined) {
            throw new IllegalStateException("determine() has not been successfully called");
        }
        for (int line = startLine; line <= endLine; line++) {
            int start = (line == startLine ? startColumn : 0);
            int end = (line == endLine ? endColumn : Integer.MAX_VALUE);
            List<Span> spans = mSpanMap.get(line);
            int increment;
            for (int i = 0; i < spans.size(); i += increment) {
                Span span = spans.get(i);
                increment = 1;
                if (span.column >= end) {
                    break;
                }
                int spanEnd = (i + 1 >= spans.size() ? Integer.MAX_VALUE : spans.get(i + 1).column);
                if (spanEnd >= start) {
                    int regionStartInSpan = Math.max(span.column, start);
                    int regionEndInSpan = Math.min(end, spanEnd);
                    if (regionStartInSpan == span.column) {
                        if (regionEndInSpan == spanEnd) {
                            span.problemFlags |= newFlag;
                        } else {
                            increment = 2;
                            Span nSpan = span.copy();
                            nSpan.column = regionEndInSpan;
                            spans.add(i + 1, nSpan);
                            span.problemFlags |= newFlag;
                        }
                    } else {
                        //regionStartInSpan > span.column
                        if (regionEndInSpan == spanEnd) {
                            increment = 2;
                            Span nSpan = span.copy();
                            nSpan.column = regionStartInSpan;
                            spans.add(i + 1, nSpan);
                            nSpan.problemFlags |= newFlag;
                        } else {
                            increment = 3;
                            Span span1 = span.copy();
                            span1.column = regionStartInSpan;
                            span1.problemFlags |= newFlag;
                            Span span2 = span.copy();
                            span2.column = regionEndInSpan;
                            spans.add(i + 1, span1);
                            spans.add(i + 2, span2);
                        }
                    }
                }
            }
        }
    }

}
