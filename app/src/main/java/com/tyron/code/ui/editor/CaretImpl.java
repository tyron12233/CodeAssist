package com.tyron.code.ui.editor;

import com.tyron.legacyEditor.Caret;

import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Cursor;

public class CaretImpl implements Caret {

    private int start;
    private int end;

    private int startLine;
    private int startColumn;

    private int endLine;
    private int endColumn;


    public CaretImpl() {

    }

    @Override
    public int getStart() {
        return start;
    }

    @Override
    public int getEnd() {
        return end;
    }

    @Override
    public int getStartLine() {
        return startLine;
    }

    @Override
    public int getStartColumn() {
        return startColumn;
    }

    @Override
    public int getEndLine() {
        return endLine;
    }

    @Override
    public int getEndColumn() {
        return endColumn;
    }

    @Override
    public boolean isSelected() {
        return start != end;
    }

    public void updateStart(CharPosition start) {
        this.start = start.getIndex();
        this.startLine = start.getLine();
        this.startColumn = start.getColumn();
    }

    public void updateEnd(CharPosition end) {
        this.end = end.getIndex();
        this.endLine = end.getLine();
        this.endColumn = end.getColumn();
    }
}
