package com.tyron.psi.tailtype;

import com.tyron.psi.completion.InsertionContext;
import com.tyron.psi.editor.Editor;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/**
 * @author peter
 */
public class CharTailType extends TailType {
    private final char myChar;
    private final boolean myOverwrite;

    public CharTailType(final char aChar) {
        this(aChar, true);
    }

    public CharTailType(char aChar, boolean overwrite) {
        myChar = aChar;
        myOverwrite = overwrite;
    }

    @Override
    public boolean isApplicable(@NotNull InsertionContext context) {
        return !context.shouldAddCompletionChar() || context.getCompletionChar() != myChar;
    }

    @Override
    public int processTail(final Editor editor, final int tailOffset) {
        return insertChar(editor, tailOffset, myChar, myOverwrite);
    }

    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof CharTailType)) return false;

        final CharTailType that = (CharTailType)o;

        if (myChar != that.myChar) return false;

        return true;
    }

    public int hashCode() {
        return myChar;
    }

    @NonNls
    public String toString() {
        return "CharTailType:'" + myChar + "'";
    }
}
