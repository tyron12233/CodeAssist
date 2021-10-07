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
package io.github.rosemoe.sora.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderNode;

import androidx.annotation.RequiresApi;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import io.github.rosemoe.sora.annotations.Experimental;
import io.github.rosemoe.sora.data.Span;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentListener;
import io.github.rosemoe.sora.util.ArrayList;

/**
 * Hardware accelerated text render, which manages {@link RenderNode}
 * to speed up drawing process.
 *
 * @author Rosemoe
 */
@RequiresApi(29)
@Experimental
class HwAcceleratedRenderer implements ContentListener {

    private final CodeEditor editor;
    private final ArrayList<TextRenderNode> cache;
    private int desired;

    public HwAcceleratedRenderer(CodeEditor editor) {
        this.editor = editor;
        cache = new ArrayList<>(64);
        setExpectedCapacity(30);
    }

    private boolean shouldUpdateCache() {
        return !editor.isWordwrap() && editor.isHardwareAcceleratedDrawAllowed();
    }

    public void setExpectedCapacity(int desired) {
        this.desired = desired;
        removeGarbage();
    }

    public void removeGarbage() {
        if (cache.size() > desired) {
            cache.removeRange(desired, cache.size());
        }
    }

    public void invalidateInRegion(int startLine, int endLine) {
        cache.forEach((node) -> {
            if (!node.isDirty && node.line >= startLine && node.line <= endLine) {
                node.isDirty = true;
            }
        });
    }

    /**
     * Called by editor when text style changes.
     * Such as text size/typeface.
     * Also called when wordwrap state changes from true to false
     */
    public void invalidate() {
        if (shouldUpdateCache()) {
            invalidateDirectly();
        }
    }

    public void invalidateDirectly() {
        cache.forEach(node -> node.isDirty = true);
    }

    public void invalidateDirtyRegions(List<List<Span>> old, List<List<Span>> updated) {
        //Simply compares hash code
        cache.forEach((node) -> {
            try {
                var olds = old.get(node.line);
                var news = updated.get(node.line);
                if (!node.needsRecord() && (olds.size() != news.size() || olds.hashCode() != news.hashCode())) {
                    node.isDirty = true;
                }
            } catch (IndexOutOfBoundsException | NullPointerException e) {
                node.isDirty = true;
            }
        });
    }

    public TextRenderNode getNode(int line) {
        var size = cache.size();
        for (int i = 0; i < size; i++) {
            var node = cache.get(i);
            if (node.line == line) {
                cache.remove(i);
                cache.add(0, node);
                return node;
            }
        }
        var node = new TextRenderNode(line);
        cache.add(0, node);
        return node;
    }

    public void keepCurrentInDisplay(int start, int end) {
        var itr = cache.iterator();
        while (itr.hasNext()) {
            var node = itr.next();
            if (node.line < start || node.line > end) {
                itr.remove();
            }
        }
    }

    public int drawLineHardwareAccelerated(Canvas canvas, int line, float offset) {
        if (!canvas.isHardwareAccelerated()) {
            throw new UnsupportedOperationException("Only hardware-accelerated canvas can be used");
        }
        var spanMap = editor.getTextAnalyzeResult().getSpanMap();
        // It's safe to use row directly because the mode is non-wordwrap
        var node = getNode(line);
        if (node.needsRecord()) {
            List<Span> spans = null;
            if (line < spanMap.size() && line >= 0) {
                spans = spanMap.get(line);
            }
            if (spans == null || spans.size() == 0) {
                spans = new ArrayList<>();
                spans.add(Span.obtain(0, EditorColorScheme.TEXT_NORMAL));
            }
            editor.updateBoringLineDisplayList(node.renderNode, line, spans);
            node.isDirty = false;
        }
        canvas.save();
        canvas.translate(offset, editor.getRowTop(line) - editor.getOffsetY());
        canvas.drawRenderNode(node.renderNode);
        canvas.restore();
        return node.renderNode.getWidth();
    }

    @Override
    public void beforeReplace(Content content) {
        //Intentionally empty
    }

    @Override
    public void afterInsert(Content content, int startLine, int startColumn, int endLine, int endColumn, CharSequence insertedContent) {
        if (shouldUpdateCache()) {
            int delta = endLine - startLine;
            cache.forEach((node) -> {
                if (node != null && node.line != -1) {
                    if (node.line == startLine) {
                        node.isDirty = true;
                    }
                    if (delta != 0 && node.line > startLine) {
                        node.line += delta;
                    }
                }
            });
        }
    }

    @Override
    public void afterDelete(Content content, int startLine, int startColumn, int endLine, int endColumn, CharSequence deletedContent) {
        if (shouldUpdateCache()) {
            int delta = endLine - startLine;
            cache.forEach((node) -> {
                if (node != null && node.line != -1) {
                    if (node.line == startLine || node.line == endLine) {
                        node.isDirty = true;
                    }
                    if (delta != 0 && node.line >= endLine) {
                        node.line -= delta;
                    }
                }
            });
        }
    }

    protected static class TextRenderNode {

        /**
         * The target line of this node.
         * -1 for unavailable
         */
        public int line;
        public RenderNode renderNode;
        public boolean isDirty;

        public TextRenderNode(int line) {
            this.line = line;
            renderNode = new RenderNode("editorRenderNode");
            isDirty = true;
        }

        public boolean needsRecord() {
            return isDirty || !renderNode.hasDisplayList();
        }

    }
}
