package com.tyron.editor;

import com.tyron.builder.model.DiagnosticWrapper;

import java.io.File;
import java.util.List;

public interface Editor {

    /**
     * Returns a mutable list of diagnostics from this editor
     * @return mutable list of diagnostics
     */
    List<DiagnosticWrapper> getDiagnostics();

    void setDiagnostics(List<DiagnosticWrapper> diagnostics);

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

    CharPosition getCharPosition(int index);

    int getCharIndex(int line, int column);

    void insert(int line, int column, String string);

    void replace(int line, int column, int endLine, int endColumn, String string);

    boolean formatCodeAsync();

    boolean formatCodeAsync(int startIndex, int endIndex);

    void beginBatchEdit();

    void endBatchEdit();

    Caret getCaret();

    Content getContent();

    void setSelectionRegion(int line, int column, int endLine, int endColumn);
}
