package com.tyron.code.ui.editor.impl.text.rosemoe;

import com.tyron.editor.Caret;

import io.github.rosemoe.sora.text.Cursor;

public class CursorWrapper implements Caret {

    private final Cursor mCursor;

    public CursorWrapper(Cursor cursor) {
        mCursor = cursor;
    }


    @Override
    public int getStart() {
        return mCursor.getLeft();
    }

    @Override
    public int getEnd() {
        return mCursor.getRight();
    }

    @Override
    public int getStartLine() {
        return mCursor.getLeftLine();
    }

    @Override
    public int getStartColumn() {
        return mCursor.getLeftColumn();
    }

    @Override
    public int getEndLine() {
        return mCursor.getRightLine();
    }

    @Override
    public int getEndColumn() {
        return mCursor.getRightColumn();
    }

    @Override
    public boolean isSelected() {
        return mCursor.isSelected();
    }
}
