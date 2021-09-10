package com.tyron.code.model;

/**
 * Class that represents an edit that should be
 * applied on the target file
 */
public class TextEdit {
    public Range range;
    public String newText;

    public TextEdit() {}

    public TextEdit(Range range, String newText) {
        this.range = range;
        this.newText = newText;
    }

    @Override
    public String toString() {
        return range + "/" + newText;
    }

    public static final TextEdit NONE = new TextEdit(Range.NONE, "");
}
