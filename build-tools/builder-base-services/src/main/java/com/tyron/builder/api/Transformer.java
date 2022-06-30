package com.tyron.builder.api;

/**
 * <p>A {@code Transformer} transforms objects of type.</p>
 *
 * <p>Implementations are free to return new objects or mutate the incoming value.</p>
 *
 * @param <OUT> The type the value is transformed to.
 * @param <IN> The type of the value to be transformed.
 */
public interface Transformer<OUT, IN> {
    /**
     * Transforms the given object, and returns the transformed value.
     *
     * @param in The object to transform.
     * @return The transformed object.
     */
    OUT transform(IN in);
}