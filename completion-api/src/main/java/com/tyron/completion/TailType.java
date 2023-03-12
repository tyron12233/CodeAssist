package com.tyron.completion;

import com.tyron.completion.lookup.LookupElement;
import com.tyron.editor.Editor;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.Document;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * An object representing a simple document change done at
 * {@link InsertionContext#getTailOffset()} after completion,
 * namely, inserting a character, sometimes with spaces for formatting.
 * Please consider putting this logic into {@link LookupElement#handleInsert} or
 * {@link InsertHandler},
 * as they're more flexible, and having all document modification code in one place will probably
 * be more comprehensive.
 */
public abstract class TailType {

    public static int insertChar(final Editor editor, final int tailOffset, final char c) {
        return insertChar(editor, tailOffset, c, true);
    }

    public static int insertChar(Editor editor, int tailOffset, char c, boolean overwrite) {
        Document document = editor.getDocument();
        int textLength = document.getTextLength();
        if (tailOffset == textLength ||
            !overwrite ||
            ((CharSequence) document).charAt(tailOffset) != c) {
            document.insertString(tailOffset, String.valueOf(c));
        }
        return moveCaret(editor, tailOffset, 1);
    }

    protected static int moveCaret(final Editor editor, final int tailOffset, final int delta) {
        return tailOffset + delta;
    }

    public static final TailType UNKNOWN = new TailType() {
        @Override
        public int processTail(final Editor editor, final int tailOffset) {
            return tailOffset;
        }

        public String toString() {
            return "UNKNOWN";
        }
    };

    public static final TailType NONE = new TailType() {
        @Override
        public int processTail(final Editor editor, final int tailOffset) {
            return tailOffset;
        }

        public String toString() {
            return "NONE";
        }
    };


    public abstract int processTail(final Editor editor, int tailOffset);

    public static TailType createSimpleTailType(final char c) {
        return new CharTailType(c);
    }

    /**
     * insert a space, overtype if already present
     */
    public static final TailType SPACE = new CharTailType(' ');
    /**
     * always insert a space
     */
    public static final TailType INSERT_SPACE = new CharTailType(' ', false);
    /**
     * insert a space unless there's one at the caret position already, followed by a word or '@'
     */
    public static final TailType HUMBLE_SPACE_BEFORE_WORD = new CharTailType(' ', false) {

        @Override
        public boolean isApplicable(@NotNull InsertionContext context) {
            CharSequence text = context.getEditor().getDocument().getCharsSequence();
            int tail = context.getTailOffset();
            if (text.length() > tail + 1 && text.charAt(tail) == ' ') {
                char ch = text.charAt(tail + 1);
                if (ch == '@' || Character.isLetter(ch)) {
                    return false;
                }
            }
            return super.isApplicable(context);
        }

        @Override
        public String toString() {
            return "HUMBLE_SPACE_BEFORE_WORD";
        }
    };
    public static final TailType DOT = new CharTailType('.');

    public static final TailType CASE_COLON = new CharTailType(':');
    public static final TailType SEMICOLON = new CharTailType(';');

    public static final TailType EQUALS = new CharTailType('=');

    public boolean isApplicable(@NotNull final InsertionContext context) {
        return true;
    }
}
