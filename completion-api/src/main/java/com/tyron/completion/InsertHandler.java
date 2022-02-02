package com.tyron.completion;

import com.tyron.editor.Editor;

/**
 * Interface for customizing the how the completion item inserts the text
 */
public interface InsertHandler {

    void handleInsert(Editor editor);
}
