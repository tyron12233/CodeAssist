package com.tyron.completion.model;

public enum DrawableKind {
    Method("m", 0xffe92e2e),
    Interface("I", 0xffcc7832),
    Filed("F", 0xffcc7832),
    Class("C", 0xff1c9344),
    Keyword("K", 0xffcc7832),
    Package("P", 0xffcc7832),
    Lambda("λ", 0xff36b9da),
    Snippet("S", 0xffcc7832),
    LocalVariable("V", 0xffcc7832);


    private final int color;
    private final String prefix;

    DrawableKind(String prefix, int color) {
        this.prefix = prefix;
        this.color = color;
    }

    public String getValue() {
        return prefix;
    }

    public int getColor() {
        return color;
    }
}
