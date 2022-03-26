package com.tyron.builder.api.internal;

import org.jetbrains.annotations.Nullable;

public abstract class Cast {

    /**
     * Casts the given object to the given type, providing a better error message than the default.
     * <p>
     * The standard {@link Class#cast(Object)} method produces unsatisfactory error messages on
     * some platforms
     * when it fails. All this method does is provide a better, consistent, error message.
     * <p>
     * This should be used whenever there is a chance the cast could fail. If in doubt, use this.
     *
     * @param outputType The type to cast the input to
     * @param object     The object to be cast (must not be {@code null})
     * @param <O>        The type to be cast to
     * @param <I>        The type of the object to be vast
     * @return The input object, cast to the output type
     */
    public static <O, I> O cast(Class<O> outputType, I object) {
        try {
            return outputType.cast(object);
        } catch (ClassCastException e) {
            throw new ClassCastException(
                    String.format("Failed to cast object %s of type %s to target type %s", object,
                                  object.getClass().getName(), outputType.getName()));
        }
    }

    /**
     * Casts the given object to the given type, providing a better error message than the default.
     * <p>
     * The standard {@link Class#cast(Object)} method produces unsatisfactory error messages on
     * some platforms
     * when it fails. All this method does is provide a better, consistent, error message.
     * <p>
     * This should be used whenever there is a chance the cast could fail. If in doubt, use this.
     *
     * @param outputType The type to cast the input to
     * @param object     The object to be cast
     * @param <O>        The type to be cast to
     * @param <I>        The type of the object to be vast
     * @return The input object, cast to the output type
     */
    @Nullable
    public static <O, I> O castNullable(Class<O> outputType, @Nullable I object) {
        if (object == null) {
            return null;
        }
        return cast(outputType, object);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static <T> T uncheckedCast(@Nullable Object object) {
        return (T) object;
    }

    @SuppressWarnings("unchecked")
    public static <T> T uncheckedNonnullCast(Object object) {
        return (T) object;
    }
}