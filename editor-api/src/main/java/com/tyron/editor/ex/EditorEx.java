package com.tyron.editor.ex;

import com.tyron.editor.Editor;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.editor.ex.DocumentEx;
import org.jetbrains.kotlin.com.intellij.openapi.util.Key;
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;

public interface EditorEx extends Editor {

    @NonNls String PROP_INSERT_MODE = "insertMode";
    @NonNls String PROP_COLUMN_MODE = "columnMode";
    @NonNls String PROP_FONT_SIZE = "fontSize";
    @NonNls String PROP_FONT_SIZE_2D = "fontSize2D";
    @NonNls String PROP_ONE_LINE_MODE = "oneLineMode";
    @NonNls String PROP_HIGHLIGHTER = "highlighter";
    Key<TextRange> LAST_PASTED_REGION = Key.create("LAST_PASTED_REGION");

    @NotNull
    @Override
    DocumentEx getDocument();

    /**
     * shouldn't be called during Document update
     */
    void setViewer(boolean isViewer);

//    void setHighlighter(@NotNull EditorHighlighter highlighter);

//    void setPermanentHeaderComponent(JComponent component);

//    void setColorsScheme(@NotNull EditorColorsScheme scheme);

    void setInsertMode(boolean val);

    void setColumnMode(boolean val);

    void reinitSettings();

    void setFile(VirtualFile vFile);

    void setFontSize(int fontSize);

    default void setFontSize(float fontSize) {
        setFontSize((int)(fontSize + 0.5));
    }
}
