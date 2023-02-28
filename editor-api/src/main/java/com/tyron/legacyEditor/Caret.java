package com.tyron.legacyEditor;

public interface Caret {

    int getStart();

    int getEnd();

    int getStartLine();

    int getStartColumn();

    int getEndLine();

    int getEndColumn();

    boolean isSelected();
}
