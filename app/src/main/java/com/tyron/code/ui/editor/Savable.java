package com.tyron.code.ui.editor;

/**
 * Fragments may implement this class to indicate that its contents
 * can be saved.
 */
public interface Savable {

    /**
     * Saves the contents of this class
     * @param toDisk whether the contents will be written to file or stored in memory
     */
    void save(boolean toDisk);
}
