package com.tyron.psi.completions.lang.java.search.searches;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.com.intellij.openapi.project.DumbAware;
import org.jetbrains.kotlin.com.intellij.openapi.project.Project;
import org.jetbrains.kotlin.com.intellij.util.QueryParameters;

/**
 * A marker interface for {@link QueryExecutorBase} parameters which indicates that searches should only be executed when indexing is complete.
 * The query executors that are not {@link DumbAware} are delayed in processing until index is ready.
 *
 * @author peter
 */
public interface DumbAwareSearchParameters extends QueryParameters {
    @Override
    @NotNull
    Project getProject();
}
