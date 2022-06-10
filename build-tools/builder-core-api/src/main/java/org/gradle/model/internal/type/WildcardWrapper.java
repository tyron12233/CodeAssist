package org.gradle.model.internal.type;


import org.jetbrains.annotations.Nullable;

public interface WildcardWrapper extends TypeWrapper {
    TypeWrapper getUpperBound();

    @Nullable
    TypeWrapper getLowerBound();
}