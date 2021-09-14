package com.tyron.lint.api;

import com.tyron.completion.model.Position;

/**
 * A simple offset-based position *
 */
public class DefaultPosition extends Position {
    /** The line number (0-based where the first line is line 0) */
    private final int mLine;

    /**
     * The column number (where the first character on the line is 0), or -1 if
     * unknown
     */
    private final int mColumn;

    /** The character offset */
    private final int mOffset;

    /**
     * Creates a new {@link DefaultPosition}
     *
     * @param line the 0-based line number, or -1 if unknown
     * @param column the 0-based column number, or -1 if unknown
     * @param offset the offset, or -1 if unknown
     */
    public DefaultPosition(int line, int column, int offset) {
        super(line, column);
        mLine = line;
        mColumn = column;
        mOffset = offset;
    }

    public int getLine() {
        return mLine;
    }

    public int getOffset() {
        return mOffset;
    }

    public int getColumn() {
        return mColumn;
    }
}
