package com.tyron.builder.internal;

import com.tyron.builder.api.Action;

import java.util.function.Predicate;

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

    /**
     * Creates a new action that only forwards arguments on to the given filter if they are satisfied by the given spec.
     *
     * @param action The action to delegate filtered items to
     * @param filter The spec to use to filter items by
     * @param <T> The type of item the action expects
     * @return A new action that only forwards arguments on to the given filter is they are satisfied by the given spec.
     */
    public static <T> Action<T> filter(Action<? super T> action, Predicate<? super T> filter) {
        return new FilteredAction<T>(action, filter);
    }

    private static class FilteredAction<T> implements Action<T> {
        private final Predicate<? super T> filter;
        private final Action<? super T> action;

        public FilteredAction(Action<? super T> action, Predicate<? super T> filter) {
            this.filter = filter;
            this.action = action;
        }

        @Override
        public void execute(T t) {
            if (filter.test(t)) {
                action.execute(t);
            }
        }
    }
}
