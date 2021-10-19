package com.tyron.completions;

import com.tyron.lookup.LookupElementWeigher;

import org.jetbrains.annotations.NotNull;

/**
 * @author peter
 */
public abstract class CompletionSorter {
    public abstract CompletionSorter weighBefore(@NotNull String beforeId, LookupElementWeigher... weighers);

    public abstract CompletionSorter weighAfter(@NotNull String afterId, LookupElementWeigher... weighers);

    public abstract CompletionSorter weigh(LookupElementWeigher weigher);

    public static CompletionSorter emptySorter() {
        return CompletionService.getCompletionService().emptySorter();
    }

    public static CompletionSorter defaultSorter(CompletionParameters parameters, PrefixMatcher matcher) {
        return CompletionService.getCompletionService().defaultSorter(parameters, matcher);
    }

}
