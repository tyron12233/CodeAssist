package org.jetbrains.kotlin.com.intellij.openapi.util;

import androidx.annotation.NonNull;

@FunctionalInterface
public interface ThrowableNotNullFunction<T, R, E extends Throwable> {
    @NonNull
    R fun(@NonNull T t) throws E;
}