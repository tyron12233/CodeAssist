package com.tyron.builder.gradle.internal.ide.level2;

import com.android.annotations.NonNull;
import com.tyron.builder.model.level2.DependencyGraphs;
import com.tyron.builder.model.level2.GraphItem;
import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

public class SimpleDependencyGraphsImpl implements DependencyGraphs, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final List<GraphItem> items;
    @NonNull private final List<String> providedLibraries;
    private final int hashCode;

    public SimpleDependencyGraphsImpl(
            @NonNull List<GraphItem> items, @NonNull List<String> providedLibraries) {
        this.items = items;
        this.providedLibraries = ImmutableList.copyOf(providedLibraries);
        this.hashCode = computeHashCode();
    }

    @NonNull
    @Override
    public List<GraphItem> getCompileDependencies() {
        return items;
    }

    @NonNull
    @Override
    public List<GraphItem> getPackageDependencies() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public List<String> getProvidedLibraries() {
        return providedLibraries;
    }

    @NonNull
    @Override
    public List<String> getSkippedLibraries() {
        return Collections.emptyList();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SimpleDependencyGraphsImpl that = (SimpleDependencyGraphsImpl) o;

        return items.equals(that.items) && providedLibraries.equals(that.providedLibraries);

    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private int computeHashCode() {
        int result = items.hashCode();
        result = 31 * result + providedLibraries.hashCode();
        return result;
    }
}
