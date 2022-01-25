package com.tyron.code.ui.editor.language;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.completion.model.CompletionList;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import io.github.rosemoe.sora.data.CompletionItem;
import io.github.rosemoe.sora.interfaces.AutoCompleteProvider;
import io.github.rosemoe.sora.text.TextAnalyzeResult;

/**
 * An auto complete provider that supports cancellation as the user types
 */
public abstract class AbstractAutoCompleteProvider implements AutoCompleteProvider {

    private CompletableFuture<CompletionList> mPreviousTask;

    @Override
    public final List<CompletionItem> getAutoCompleteItems(String prefix, TextAnalyzeResult colors, int line, int column) {
        if (mPreviousTask != null && !mPreviousTask.isDone()) {
            try {
                mPreviousTask.cancel(true);
            } catch (Throwable e) {
                return null;
            }
        }

        mPreviousTask = getCompletionList(prefix, colors, line, column);
        if (mPreviousTask == null) {
            return null;
        }

        try {
            CompletionList completionList = mPreviousTask.get();
            return completionList.items.stream()
                    .map(CompletionItem::new)
                    .collect(Collectors.toList());
        } catch (ExecutionException | InterruptedException e) {
            return null;
        }
    }

    @Nullable
    public abstract CompletableFuture<CompletionList> getCompletionList(String prefix, TextAnalyzeResult colors, int line, int column);
}
