package com.tyron.psi.editor;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

public class CaretState {
    private final LogicalPosition caretPosition;
    private final int visualColumnAdjustment;
    private final LogicalPosition selectionStart;
    private final LogicalPosition selectionEnd;

    public CaretState(@Nullable LogicalPosition caretPosition,
                      @Nullable LogicalPosition selectionStart,
                      @Nullable LogicalPosition selectionEnd) {
        this(caretPosition, 0, selectionStart, selectionEnd);
    }

    /**
     * @param visualColumnAdjustment see {@link #getVisualColumnAdjustment()}
     */
    public CaretState(@Nullable LogicalPosition caretPosition,
                      int visualColumnAdjustment,
                      @Nullable LogicalPosition selectionStart,
                      @Nullable LogicalPosition selectionEnd) {
        this.caretPosition = caretPosition;
        this.visualColumnAdjustment = visualColumnAdjustment;
        this.selectionStart = selectionStart;
        this.selectionEnd = selectionEnd;
    }

    @Nullable
    public LogicalPosition getCaretPosition(){
        return caretPosition;
    }

    /**
     * Sometimes logical caret position is not fully determining its visual position (e.g. around inlays). This value should be added to the
     * result of {@code editor.logicalToVisualPosition(caretState.getCaretPosition())}'s column,
     * if one needs to calculate caret's visual position.
     */
    public int getVisualColumnAdjustment() {
        return visualColumnAdjustment;
    }

    @Nullable
    public LogicalPosition getSelectionStart() {
        return selectionStart;
    }

    @Nullable
    public LogicalPosition getSelectionEnd() {
        return selectionEnd;
    }

    @Override
    @NonNls
    public String toString() {
        return "CaretState{" +
                "caretPosition=" + caretPosition +
                (visualColumnAdjustment == 0 ? "" : (", visualColumnAdjustment=" + visualColumnAdjustment)) +
                ", selectionStart=" + selectionStart +
                ", selectionEnd=" + selectionEnd +
                '}';
    }
}