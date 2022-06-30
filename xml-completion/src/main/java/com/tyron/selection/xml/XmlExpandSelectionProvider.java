package com.tyron.selection.xml;

import android.util.Pair;

import com.google.common.collect.Range;
import com.tyron.editor.Caret;
import com.tyron.editor.Editor;
import com.tyron.editor.selection.ExpandSelectionProvider;

import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class XmlExpandSelectionProvider extends ExpandSelectionProvider {
    @Override
    public @Nullable Range<Integer> expandSelection(Editor editor) {
        String contents = editor.getContent()
                .toString();
        DOMDocument parsed = DOMParser.getInstance()
                .parse(contents, "", null);
        if (parsed == null) {
            return null;
        }

        Caret caret = editor.getCaret();
        int cursorStart = caret.getStart();
        int cursorEnd = caret.getEnd();
        DOMNode node = findNode(parsed, 0, Pair.create(cursorStart, cursorEnd));
        if (node == null) {
            return null;
        }

        if (node.getStart() == cursorStart && node.getEnd() == cursorEnd) {
            DOMNode parentNode = node.getParentNode();
            if (parentNode.getStart() != -1 && parentNode.getEnd() != -1) {
                return Range.closed(parentNode.getStart(), parentNode.getEnd());
            }
        }
        return Range.closed(node.getStart(), node.getEnd());
    }

    private DOMNode findNode(DOMNode node, int level, Pair<Integer, Integer> cursor) {
        if (node instanceof DOMElement) {
            List<DOMAttr> attributeNodes = node.getAttributeNodes();
            if (attributeNodes != null) {
                for (DOMAttr attr : attributeNodes) {
                    if (isInside(attr, cursor)) {
                        return attr;
                    }
                }
            }
        }
        if (isInside(node, cursor)) {
            return node;
        }
        List<DOMNode> list = node.getChildren();
        for (DOMNode domNode : list) {
            findNode(domNode, level + 1, cursor);
        }
        return null;
    }

    private boolean isInside(DOMNode node, Pair<Integer, Integer> cursor) {
        if (node.getStart() == -1 || node.getEnd() == -1) {
            return false;
        }
        return node.getStart() >= cursor.first && cursor.second >= node.getEnd();
    }
}
