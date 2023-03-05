package com.tyron.code.event;

import com.tyron.code.ui.editor.shortcuts.ShortcutItem;
import com.tyron.fileeditor.api.FileEditor;

public class PerformShortcutEvent extends Event {

    private final ShortcutItem item;
    private final FileEditor editor;

    public PerformShortcutEvent(ShortcutItem item, FileEditor editor) {
        this.item = item;
        this.editor = editor;
    }

    public ShortcutItem getItem() {
        return item;
    }

    public FileEditor getEditor() {
        return editor;
    }
}
