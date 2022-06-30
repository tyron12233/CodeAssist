package com.tyron.code.language;

import com.tyron.completion.model.CompletionList;

import java.util.List;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.lang.completion.CompletionItem;

/**
 * An auto complete provider that supports cancellation as the user types
 */
public abstract class AbstractAutoCompleteProvider {

    public final List<CompletionItem> getAutoCompleteItems(String prefix, int line, int column) {
        CompletionList list = getCompletionList(prefix, line, column);
        if (list == null) {
            return null;
        }

        return list.items.stream()
               .map(CompletionItemWrapper::new)
               .collect(Collectors.toList());
    }

    public abstract CompletionList getCompletionList(String prefix, int line, int column);
}
