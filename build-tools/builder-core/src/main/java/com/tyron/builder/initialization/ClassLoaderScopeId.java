package com.tyron.builder.initialization;

import javax.annotation.Nullable;

/**
 * Uniquely identifies a {@link ClassLoaderScope} in
 * the {@link ClassLoaderScopeRegistry}.
 */
public interface ClassLoaderScopeId {

    @Nullable
    ClassLoaderScopeId getParent();

    String getName();

    boolean equals(Object other);

    int hashCode();
}

