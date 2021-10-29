package com.tyron.psi.completions.lang.java.simple;

import com.tyron.psi.codeStyle.CommonCodeStyleSettings;
import com.tyron.psi.editor.Editor;
import com.tyron.psi.tailtype.TailType;

import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;

/**
 * @author peter
 */
public abstract class ParenthesesTailType extends TailType {

    protected abstract boolean isSpaceBeforeParentheses(CommonCodeStyleSettings styleSettings, Editor editor, final int tailOffset);

    protected abstract boolean isSpaceWithinParentheses(CommonCodeStyleSettings styleSettings, Editor editor, final int tailOffset);

    @Override
    public int processTail(final Editor editor, int tailOffset) {
        CommonCodeStyleSettings styleSettings = getLocalCodeStyleSettings(editor, tailOffset);
        if (isSpaceBeforeParentheses(styleSettings, editor, tailOffset)) {
            tailOffset = insertChar(editor, tailOffset, ' ');
        }
        Document document = editor.getDocument();
        if (tailOffset < document.getTextLength() && document.getCharsSequence().charAt(tailOffset) == '(') {
            return moveCaret(editor, tailOffset, 1);
        }

        tailOffset = insertChar(editor, tailOffset, '(');
        if (isSpaceWithinParentheses(styleSettings, editor, tailOffset)) {
            tailOffset = insertChar(editor, tailOffset, ' ');
            tailOffset = insertChar(editor, tailOffset, ' ');
            tailOffset = insertChar(editor, tailOffset, ')');
            moveCaret(editor, tailOffset, -2);
        } else {
            tailOffset = insertChar(editor, tailOffset, ')');
            moveCaret(editor, tailOffset, -1);
        }
        return tailOffset;
    }

}