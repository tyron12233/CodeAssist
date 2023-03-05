package com.tyron.builder.gradle.internal.ide.level2;

import com.android.annotations.NonNull;
import com.tyron.builder.model.level2.DependencyGraphs;
import com.tyron.builder.model.level2.GraphItem;
import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.List;

public class FullDependencyGraphsImpl implements DependencyGraphs, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull
    private final List<GraphItem> compileItems;
    @NonNull
    private final List<GraphItem> packageItems;
    @NonNull
    private final List<String> providedLibraries;
    @NonNull
    private final List<String> skippedLibraries;
    private final int hashCode;

    public FullDependencyGraphsImpl(
            @NonNull List<GraphItem> compileItems,
            @NonNull List<GraphItem> packageItems,
            @NonNull List<String> providedLibraries,
            @NonNull List<String> skippedLibraries) {
        this.compileItems = compileItems;
        this.packageItems = packageItems;
        this.providedLibraries = ImmutableList.copyOf(providedLibraries);
        this.skippedLibraries = ImmutableList.copyOf(skippedLibraries);
        this.hashCode = computeHashCode();
    }

    @NonNull
    @Override
    public List<GraphItem> getCompileDependencies() {
        return compileItems;
    }

    @NonNull
    @Override
    public List<GraphItem> getPackageDependencies() {
        return packageItems;
    }

    @NonNull
    @Override
    public List<String> getProvidedLibraries() {
        return providedLibraries;
    }

    @NonNull
    @Override
    public List<String> getSkippedLibraries() {
        return skippedLibraries;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        FullDependencyGraphsImpl that = (FullDependencyGraphsImpl) o;

        if (!compileItems.equals(that.compileItems)) {
            return false;
        }
        if (!packageItems.equals(that.packageItems)) {
            return false;
        }
        if (!providedLibraries.equals(that.providedLibraries)) {
            return false;
        }
        return skippedLibraries.equals(that.skippedLibraries);

    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    private int computeHashCode() {
        int result = compileItems.hashCode();
        result = 31 * result + packageItems.hashCode();
        result = 31 * result + providedLibraries.hashCode();
        result = 31 * result + skippedLibraries.hashCode();
        return result;
    }
}
