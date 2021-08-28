package com.tyron.code.editor.shortcuts;

public class ShortcutAction {
    public enum Kind {
        TextEdit,
        CursorMove
    }

    public Kind kind;

    public String data;

    public int start;

    public int end;
}
