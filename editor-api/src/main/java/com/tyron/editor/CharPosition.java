package com.tyron.editor;

public class CharPosition {

    private final int mLine;
    private final int mColumn;

    public CharPosition(int line, int column) {
        mLine = line;
        mColumn = column;
    }

    public int getLine() {
        return mLine;
    }

    public int getColumn() {
        return mColumn;
    }
}
