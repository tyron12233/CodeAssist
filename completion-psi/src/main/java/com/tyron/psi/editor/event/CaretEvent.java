package com.tyron.psi.editor.event;

import com.tyron.psi.editor.Caret;
import com.tyron.psi.editor.Editor;
import com.tyron.psi.editor.LogicalPosition;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EventObject;

public class CaretEvent extends EventObject {
    private final Caret myCaret;
    private final LogicalPosition myOldPosition;
    private final LogicalPosition myNewPosition;

    public CaretEvent(@NotNull Caret caret, @NotNull LogicalPosition oldPosition, @NotNull LogicalPosition newPosition) {
        super(caret.getEditor());
        myCaret = caret;
        myOldPosition = oldPosition;
        myNewPosition = newPosition;
    }

    @NotNull
    public Editor getEditor() {
        return (Editor) getSource();
    }

    @Nullable
    public Caret getCaret() {
        return myCaret;
    }

    @NotNull
    public LogicalPosition getOldPosition() {
        return myOldPosition;
    }

    @NotNull
    public LogicalPosition getNewPosition() {
        return myNewPosition;
    }
}