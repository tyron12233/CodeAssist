package com.tyron.resolver;

public interface ResolveTask<T> {
    void onResult(T result);

    default void onError(String message) {

    }
}
