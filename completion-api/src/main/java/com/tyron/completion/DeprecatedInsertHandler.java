package com.tyron.completion;

import com.tyron.editor.Editor;

/**
 * Interface for customizing the how the completion item inserts the text
 */
@Deprecated
public interface DeprecatedInsertHandler {

    void handleInsert(Editor editor);
}
