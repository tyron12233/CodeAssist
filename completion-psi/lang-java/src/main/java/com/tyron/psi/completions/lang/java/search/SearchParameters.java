package com.tyron.psi.completions.lang.java.search;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;

/**
 * Base interface for search parameters.
 *
 * @param <R> type of search result, it is used to bind type of search parameters to the type of {@link Query}
 */
public interface SearchParameters<R> {

    @NotNull
    Project getProject();

    boolean areValid();
}
