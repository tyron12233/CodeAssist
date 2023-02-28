package com.tyron.code.language;

import com.tyron.completion.model.CompletionList;
import com.tyron.legacyEditor.Editor;

import java.util.List;

import io.github.rosemoe.sora.lang.completion.CompletionItem;

/**
 * An auto complete provider that supports cancellation as the user types
 */
@Deprecated
public abstract class AbstractAutoCompleteProvider {

    public final List<CompletionItem> getAutoCompleteItems(String prefix, int line, int column) {
        CompletionList list = getCompletionList(prefix, line, column);
        if (list == null) {
            return null;
        }

        throw new UnsupportedOperationException();
    }

    public abstract CompletionList getCompletionList(String prefix, int line, int column);

    public abstract String getPrefix(Editor editor, int line, int column);
}
