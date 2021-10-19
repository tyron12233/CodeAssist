package com.tyron.completions;

import com.tyron.lookup.LookupElement;
import com.tyron.psi.statistics.Statistician;
import com.tyron.psi.statistics.StatisticsInfo;

import org.jetbrains.annotations.NotNull;

/**
 * A {@link Statistician} for code completion, the results are used for sorting and preselection.
 */
public abstract class CompletionStatistician extends Statistician<LookupElement,CompletionLocation> {
    @Override
    public abstract StatisticsInfo serialize(@NotNull final LookupElement element, @NotNull final CompletionLocation location);
}
