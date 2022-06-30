package com.tyron.builder.internal;

import com.tyron.builder.api.Action;

/**
 * A mutable composite {@link Action}. Actions are executed in the order added, stopping on the first failure.
 *
 * This type is not thread-safe.
 *
 * Consider using {@link com.tyron.builder.internal.ImmutableActionSet} instead of this.
 *
 * Implements {@link InternalListener} as components themselves should be decorated if appropriate.
 */
public class MutableActionSet<T> implements Action<T>, InternalListener {
    private ImmutableActionSet<T> actions = ImmutableActionSet.empty();

    public void add(Action<? super T> action) {
        this.actions = actions.add(action);
    }

    @Override
    public void execute(T t) {
        actions.execute(t);
    }

    public boolean isEmpty() {
        return actions.isEmpty();
    }
}
