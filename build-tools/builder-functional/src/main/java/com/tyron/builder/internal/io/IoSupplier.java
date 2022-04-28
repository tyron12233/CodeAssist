package com.tyron.builder.internal.io;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;

/**
 * A variant of {@link Supplier} that is allowed to throw {@link IOException}.
 */
@FunctionalInterface
public interface IoSupplier<T> {
    @Nullable
    T get() throws IOException;

    /**
     * Wraps an {@link IOException}-throwing {@link IoSupplier} into a regular {@link Supplier}.
     *
     * Any {@code IOException}s are rethrown as {@link UncheckedIOException}.
     */
    static <T> Supplier<T> wrap(IoSupplier<T> supplier) {
        return () -> {
            try {
                return supplier.get();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }
}