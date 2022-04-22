package com.tyron.builder.internal.io;

import java.io.IOException;

public interface IOQuery<T> {
    Result<T> run() throws IOException, InterruptedException;

    abstract class Result<T> {
        public abstract boolean isSuccessful();

        public abstract T getValue();

        /**
         * Creates a result that indicates that the operation was successful and should not be repeated.
         */
        public static <T> Result<T> successful(final T value) {
            if (value == null) {
                throw new IllegalArgumentException();
            }
            return new Result<T>() {
                @Override
                public boolean isSuccessful() {
                    return true;
                }

                @Override
                public T getValue() {
                    return value;
                }
            };
        }

        /**
         * Creates a result that indicates that the operation was not successful and should be repeated.
         */
        public static <T> Result<T> notSuccessful(final T value) {
            if (value == null) {
                throw new IllegalArgumentException();
            }
            return new Result<T>() {
                @Override
                public boolean isSuccessful() {
                    return false;
                }

                @Override
                public T getValue() {
                    return value;
                }
            };
        }
    }
}