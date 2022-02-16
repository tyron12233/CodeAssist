package com.tyron.editor;

import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.List;

public interface Editor {

    /**
     * @return The project this editor is associated with
     */
    @Nullable
    Project getProject();

    /**
     * Returns a mutable list of diagnostics from this editor
     * @return mutable list of diagnostics
     */
    List<DiagnosticWrapper> getDiagnostics();

    void setDiagnostics(List<DiagnosticWrapper> diagnostics);

    boolean isBackgroundAnalysisEnabled();

    /**
     * Get the current file opened in the editor
     * @return the file opened in the editor
     */
    File getCurrentFile();

    /**
     * Open the file for editing
     * @param file The file, must not be null
     */
    void openFile(File file);

    /**
     * Get the CharPosition object containing line and column information from an index
     * @param index the index should be > 0
     * @return the CharPosition object
     */
    CharPosition getCharPosition(int index);

    /**
     * Get the index of the given line and column
     * @param line 0-based line
     * @param column 0-based column
     * @return the index
     */
    int getCharIndex(int line, int column);

    /**
     * @return Whether the editor uses tab instead of white spaces.
     */
    boolean useTab();

    int getTabCount();

    /**
     * Inserts the text at the specified line and column
     * @param line 0-based line
     * @param column 0-based column
     * @param string the text to insert
     */
    void insert(int line, int column, String string);

    void insertMultilineString(int line, int column, String string);

    /**
     * Deletes the text from the start to end
     * @param startLine 0-based start line
     * @param startColumn 0-based start column
     * @param endLine 0-based end line
     * @param endColumn 0-based end column
     */
    void delete(int startLine, int startColumn, int endLine, int endColumn);

    /**
     * Deletes the text within the specified index
     * @param startIndex the start index from the text
     * @param endIndex the end index from the text
     */
    void delete(int startIndex, int endIndex);

    /**
     * Replace the given range of line and column with the specified text
     */
    void replace(int line, int column, int endLine, int endColumn, String string);

    /**
     * Format the the current text asynchronously
     * @return whether the format has been successful.
     */
    boolean formatCodeAsync();

    /**
     * Format a specific range of text asynchronously
     * @param startIndex the start index
     * @param endIndex the end index
     * @return whether the format has been successful.
     */
    boolean formatCodeAsync(int startIndex, int endIndex);

    /**
     * Begin a batch edit where all of the edits made after this call will be
     * registered as one single action that can be undone
     */
    void beginBatchEdit();

    void endBatchEdit();

    /**
     * Get the caret representing the position of the cursor in the editor
     * @return the caret object
     */
    Caret getCaret();

    /**
     * @return The object representing the content of the editor
     */
    Content getContent();

    // --- CURSOR RELATED --- //

    /**
     * Set the cursor position on the specified line and column
     * @param line 0-based line
     * @param column 0-based column
     */
    void setSelection(int line, int column);

    /**
     * Sets the caret to the specified range
     */
    void setSelectionRegion(int line, int column, int endLine, int endColumn);

    void setSelectionRegion(int startIndex, int endIndex);

    void moveSelectionUp();

    void moveSelectionDown();

    void moveSelectionLeft();

    void moveSelectionRight();

    /**
     * Notify the editor to show a progress that it is analyzing in background
     * @param analyzing whether to show the progress bar.
     */
    void setAnalyzing(boolean analyzing);
}
