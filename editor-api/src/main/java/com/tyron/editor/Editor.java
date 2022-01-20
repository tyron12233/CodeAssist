package com.tyron.editor;

public interface Editor {

    CharPosition getCharPosition(int index);

    int getCharIndex(int line, int column);

    void insert(int line, int column, String string);

    void replace(int line, int column, int endLine, int endColumn, String string);

    boolean formatCodeAsync();

    boolean formatCodeAsync(int startIndex, int endIndex);

    void beginBatchEdit();

    void endBatchEdit();

    Caret getCaret();
}
