package com.tyron.builder.internal;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;

import java.util.List;
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

    /**
     * Creates an action that will call each of the given actions in order.
     *
     * @param actions The actions to make a composite of.
     * @param <T> The type of the object that action is for
     * @return The composite action.
     */
    public static <T> Action<T> composite(Iterable<? extends Action<? super T>> actions) {
        ImmutableList.Builder<Action<? super T>> builder = ImmutableList.builder();
        for (Action<? super T> action : actions) {
            if (doesSomething(action)) {
                builder.add(action);
            }
        }
        return composite(builder.build());
    }

    /**
     * Creates an action that will call each of the given actions in order.
     *
     * @param actions The actions to make a composite of.
     * @param <T> The type of the object that action is for
     * @return The composite action.
     */
    public static <T> Action<T> composite(List<? extends Action<? super T>> actions) {
        if (actions.isEmpty()) {
            return doNothing();
        }
        if (actions.size() == 1) {
            return Cast.uncheckedCast(actions.get(0));
        }
        return new CompositeAction<T>(actions);
    }

    /**
     * Creates an action that will call each of the given actions in order.
     *
     * @param actions The actions to make a composite of.
     * @param <T> The type of the object that action is for
     * @return The composite action.
     */
    @SafeVarargs
    public static <T> Action<T> composite(Action<? super T>... actions) {
        List<Action<? super T>> filtered = Lists.newArrayListWithCapacity(actions.length);
        for (Action<? super T> action : actions) {
            if (doesSomething(action)) {
                filtered.add(action);
            }
        }
        return composite(filtered);
    }

    private static class CompositeAction<T> implements Action<T> {
        private final List<? extends Action<? super T>> actions;

        private CompositeAction(List<? extends Action<? super T>> actions) {
            this.actions = actions;
        }

        @Override
        public void execute(T item) {
            for (Action<? super T> action : actions) {
                action.execute(item);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            CompositeAction<?> that = (CompositeAction<?>) o;

            if (!actions.equals(that.actions)) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            return actions.hashCode();
        }
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

    private static boolean doesSomething(Action<?> action) {
        return action != DO_NOTHING;
    }
}
