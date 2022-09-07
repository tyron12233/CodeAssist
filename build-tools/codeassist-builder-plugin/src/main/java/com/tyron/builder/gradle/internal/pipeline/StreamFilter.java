package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.tyron.builder.api.transform.QualifiedContent;
import java.util.Set;

/**
 * Predicate for filtering streams in a {@link FilterableStreamCollection}.
 */
public interface StreamFilter {

    boolean accept(
            @NonNull Set<QualifiedContent.ContentType> types,
            @NonNull Set<? super QualifiedContent.Scope> scopes);

    StreamFilter RESOURCES =
            (types, scopes) ->
                    types.contains(QualifiedContent.DefaultContentType.RESOURCES)
                            && !scopes.contains(QualifiedContent.Scope.PROVIDED_ONLY)
                            && !scopes.contains(QualifiedContent.Scope.TESTED_CODE);

    StreamFilter PROJECT_RESOURCES =
            (types, scopes) ->
                    types.contains(QualifiedContent.DefaultContentType.RESOURCES)
                            && scopes.size() == 1
                            && scopes.contains(QualifiedContent.Scope.PROJECT);
}
