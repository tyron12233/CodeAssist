package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.tyron.builder.OutputFile;
import com.tyron.builder.gradle.internal.core.Abi;
import com.tyron.builder.gradle.internal.dsl.decorator.annotation.WithLazyInitialization;

import java.util.Set;
import javax.inject.Inject;

public abstract class AbiSplitOptions extends SplitOptions
        implements com.tyron.builder.api.dsl.AbiSplit {

    @Inject
    @WithLazyInitialization(methodName = "lazyInit")
    public AbiSplitOptions() {}

    void lazyInit() {
        init();
    }

    @Override
    protected Set<String> getDefaultValues() {
        Set<String> values = Sets.newHashSet();
        for (Abi abi : Abi.getDefaultValues()) {
            values.add(abi.getTag());
        }
        return values;
    }

    @Override
    protected ImmutableSet<String> getAllowedValues() {
        ImmutableSet.Builder<String> builder = ImmutableSet.builder();
        for (Abi abi : Abi.values()) {
            builder.add(abi.getTag());
        }
        return builder.build();
    }

    /**
     * Returns the list of actual abi filters, each value of the collection is guaranteed to be non
     * null and of the possible API value.
     * @param allFilters list of applicable filters.
     */
    @NonNull
    public static ImmutableSet<String> getAbiFilters(@NonNull Set<String> allFilters) {
        ImmutableSet.Builder<String> filters = ImmutableSet.builder();
        for (@Nullable String abi : allFilters) {
            // use object equality since abi can be null.
            //noinspection StringEquality
            if (abi != OutputFile.NO_FILTER) {
                filters.add(abi);
            }
        }
        return filters.build();
    }
}