package com.tyron.builder.internal.logging.console;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

/**
 * A virtual console screen cursor. This class avoid complex screen position management.
 */
public class Cursor {
    int col; // count from left of screen, 0 = left most
    int row; // count from bottom of screen, 0 = bottom most, 1 == 2nd from bottom

    public void copyFrom(Cursor position) {
        if (position == this) {
            return;
        }
        this.col = position.col;
        this.row = position.row;
    }

    public void bottomLeft() {
        col = 0;
        row = 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (obj == this) {
            return true;
        }
        if (obj.getClass() != getClass()) {
            return false;
        }

        Cursor rhs = (Cursor) obj;
        return col == rhs.col && row == rhs.row;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(col, row);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this.getClass())
                .add("row", row)
                .add("col", col)
                .toString();
    }

    public static Cursor at(int row, int col) {
        Cursor result = new Cursor();
        result.row = row;
        result.col = col;
        return result;
    }

    public static Cursor newBottomLeft() {
        Cursor result = new Cursor();
        result.bottomLeft();
        return result;
    }

    public static Cursor from(Cursor position) {
        Cursor result = new Cursor();
        result.copyFrom(position);
        return result;
    }
}