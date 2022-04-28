package com.tyron.builder.internal;

import com.tyron.builder.api.Action;

public class Actions {

    public static final Action<?> DO_NOTHING = __ -> {};

    public static <T> Action<T> doNothing() {
        //noinspection unchecked
        return (Action<T>) DO_NOTHING;
    }

    public static <T> T with(T instance, Action<? super T> action) {
        action.execute(instance);
        return instance;
    }
}
