package com.tyron.editor;

public interface Content extends CharSequence{

    /**
     * Checks if this content can be redone to the previous action
     * @return whether this content can be redone
     */
    boolean canRedo();

    /**
     * Redo the previous action, always check if {@link #canRedo()} returns true before calling
     * this method.
     */
    void redo();

    /**
     * @return Whether this content can be reverted to the previous action
     */
    boolean canUndo();

    /**
     * Revert to the previous action
     */
    void undo();

    String getLineString(int line);
}
