package com.tyron.builder.api.attributes;

import javax.annotation.Nullable;

/**
 * Provides context about attribute compatibility checks, and allows the user to define
 * when an attribute is compatible with another.
 * <p>
 * A compatibility check will <em>never</em> be performed when the consumer and producer values are equal.
 *
 * @param <T> the concrete type of the attribute
 *
 * @since 3.3
 */
public interface CompatibilityCheckDetails<T> {
    /**
     * The value of the attribute as found on the consumer side.
     * <p>
     * Never equal to the {@link #getProducerValue()}.
     *
     * @return the value from the consumer
     */
    @Nullable
    T getConsumerValue();

    /**
     * The value of the attribute as found on the producer side.
     * <p>
     * Never equal to the {@link #getConsumerValue()}.
     *
     * @return the value from the producer
     */
    @Nullable
    T getProducerValue();

    /**
     * Calling this method will indicate that the attributes are compatible.
     */
    void compatible();

    /**
     * Calling this method will indicate that the attributes are incompatible.
     */
    void incompatible();
}
