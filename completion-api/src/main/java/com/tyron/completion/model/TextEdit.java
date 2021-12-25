package com.tyron.completion.model;

/**
 * Class that represents an edit that should be
 * applied on the target file
 */
public class TextEdit {
    public Range range;
    public String newText;
    public boolean needFormat;

    public TextEdit() {}

    public TextEdit(Range range, String newText) {
        this.range = range;
        this.newText = newText;
        this.needFormat = false;
    }

    public TextEdit(Range range, String newText, boolean needFormat) {
        this.range = range;
        this.newText = newText;
        this.needFormat = needFormat;
    }

    @Override
    public String toString() {
        return range + "/" + newText;
    }

    public static final TextEdit NONE = new TextEdit(Range.NONE, "");
}
