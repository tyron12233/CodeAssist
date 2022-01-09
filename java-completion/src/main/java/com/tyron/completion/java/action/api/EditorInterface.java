package com.tyron.completion.java.action.api;

public interface EditorInterface {

    int getCharIndex(int line, int column);

    class CharPositionWrapper {
        public int line;
        public int column;
        public int index;
    }
    
    CharPositionWrapper getCharPosition(int index);
    
    void insert(int line, int column, String string);
    
    void replace(int line, int column, int endLine, int endColumn, String string);
    
    void formatCodeAsync(int startIndex, int endIndex);

    void beginBatchEdit();

    void endBatchEdit();
}
