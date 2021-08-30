package com.tyron.code.ui.editor.shortcuts.action;

import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;

import io.github.rosemoe.editor.widget.CodeEditor;

public class CursorMoveAction implements ShortcutAction {

    public enum Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    private Direction mDirection;

    public CursorMoveAction(Direction direction, int count) {
        mDirection = direction;
    }

    @Override
    public boolean isApplicable(String kind) {
        return kind.equals("cursormove");
    }

    @Override
    public void apply(CodeEditor editor, ShortcutItem item) {
        switch (mDirection) {
            case UP: editor.moveSelectionUp(); break;
            case DOWN: editor.moveSelectionDown(); break;
            case LEFT: editor.moveSelectionLeft(); break;
            case RIGHT: editor.moveSelectionRight();
        }
    }
}
