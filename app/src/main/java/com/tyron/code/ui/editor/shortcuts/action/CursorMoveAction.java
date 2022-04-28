package com.tyron.code.ui.editor.shortcuts.action;

import com.tyron.code.ui.editor.shortcuts.ShortcutAction;
import com.tyron.code.ui.editor.shortcuts.ShortcutItem;
import com.tyron.editor.Editor;

public class CursorMoveAction implements ShortcutAction {

    public static final String KIND = "cursorMove";

    public enum Direction {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    private final Direction mDirection;

    public CursorMoveAction(Direction direction, int count) {
        mDirection = direction;
    }

    @Override
    public boolean isApplicable(String kind) {
        return KIND.equals(kind);
    }

    @Override
    public void apply(Editor editor, ShortcutItem item) {
        switch (mDirection) {
            case UP: editor.moveSelectionUp(); break;
            case DOWN: editor.moveSelectionDown(); break;
            case LEFT: editor.moveSelectionLeft(); break;
            case RIGHT: editor.moveSelectionRight();
        }
    }
}
