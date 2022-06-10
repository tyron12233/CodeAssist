package org.gradle.api.internal.provider;

import org.jetbrains.annotations.Nullable;

public interface ValueSanitizer<T> {
    @Nullable
    T sanitize(@Nullable T value);
}