package com.tyron.code.editor.shortcuts;

public class ShortcutItem {
    public enum ActionKind {
        TextEdit,
        CursorMove
    }

    public ActionKind kind;

    public Object data;

    public String name;

    public String label;
}
