package com.tyron.builder.utils;

import com.android.annotations.NonNull;
import java.util.function.Consumer;

/**
 * Consumer that can throw an {@link Exception}.
 */
@FunctionalInterface
public interface ExceptionConsumer<T> {

    /**
     * Performs an operation on the given input.
     *
     * @param input the input
     */
    void accept(@NonNull T input) throws Exception;

    /**
     * Wraps an {@link ExceptionConsumer} into a {@link Consumer} by throwing a
     * {@link RuntimeException}.
     *
     * @param exceptionConsumer the consumer that can throw an exception
     */
    @NonNull
    static <T> Consumer<T> asConsumer(@NonNull ExceptionConsumer<T> exceptionConsumer)  {
        return input -> {
            try {
                exceptionConsumer.accept(input);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }
}
