package com.tyron.editor;

public interface Caret {

    int getStart();

    int getEnd();

    int getStartLine();

    int getStartColumn();

    int getEndLine();

    int getEndColumn();

    boolean isSelected();
}
