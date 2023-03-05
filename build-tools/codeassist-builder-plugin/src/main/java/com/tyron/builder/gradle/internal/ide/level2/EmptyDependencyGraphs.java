package com.tyron.builder.gradle.internal.ide.level2;

import com.android.annotations.NonNull;
import com.tyron.builder.model.level2.DependencyGraphs;
import com.tyron.builder.model.level2.GraphItem;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/** Empty libraryGraph */
public final class EmptyDependencyGraphs implements DependencyGraphs, Serializable {
    private static final long serialVersionUID = 1L;

    @NonNull public static final DependencyGraphs EMPTY = new EmptyDependencyGraphs();

    @NonNull
    @Override
    public List<GraphItem> getCompileDependencies() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public List<GraphItem> getPackageDependencies() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public List<String> getProvidedLibraries() {
        return Collections.emptyList();
    }

    @NonNull
    @Override
    public List<String> getSkippedLibraries() {
        return Collections.emptyList();
    }
}
