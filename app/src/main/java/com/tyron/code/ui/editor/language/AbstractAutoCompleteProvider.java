package com.tyron.code.ui.editor.language;

import androidx.annotation.Nullable;

import com.tyron.completion.model.CompletionList;

import java.util.List;
import java.util.stream.Collectors;

import io.github.rosemoe.sora2.data.CompletionItem;
import io.github.rosemoe.sora2.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora2.text.TextAnalyzeResult;

/**
 * An auto complete provider that supports cancellation as the user types
 */
public abstract class AbstractAutoCompleteProvider implements AutoCompleteProvider {

    @Override
    public final List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult colors, int line, int column) {
        CompletionList list = getCompletionList(prefix, colors,
                line, column);
        if (list == null) {
            return null;
        }

        return list.items.stream()
               .map(CompletionItem::new)
               .collect(Collectors.toList());
    }

    @Nullable
    public abstract CompletionList getCompletionList(String prefix, TextAnalyzeResult colors, int line, int column);
}
