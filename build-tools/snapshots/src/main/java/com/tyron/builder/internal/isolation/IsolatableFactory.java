package com.tyron.builder.internal.isolation;

import javax.annotation.Nullable;

public interface IsolatableFactory {
    /**
     * Creates an {@link Isolatable} that reflects the <em>current</em> state of the given value. Any changes made to the value will not be visible to the {@link Isolatable} and vice versa.
     */
    <T> Isolatable<T> isolate(@Nullable T value);
}
