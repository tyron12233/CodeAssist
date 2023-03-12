package com.tyron.code.ui.editor;

import com.tyron.legacyEditor.Caret;

import io.github.rosemoe.sora.text.Cursor;

public class CaretImpl implements Caret {

    private final Cursor cursor;

    public CaretImpl(Cursor cursor) {
        this.cursor = cursor;
    }

    @Override
    public int getStart() {
        return cursor.getLeft();
    }

    @Override
    public int getEnd() {
        return cursor.getRight();
    }

    @Override
    public int getStartLine() {
        return cursor.getLeftLine();
    }

    @Override
    public int getStartColumn() {
        return cursor.getLeftColumn();
    }

    @Override
    public int getEndLine() {
        return cursor.getRightLine();
    }

    @Override
    public int getEndColumn() {
        return cursor.getRightColumn();
    }

    @Override
    public boolean isSelected() {
        return cursor.isSelected();
    }
}
