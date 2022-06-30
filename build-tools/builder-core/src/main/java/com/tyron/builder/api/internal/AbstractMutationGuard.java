package com.tyron.builder.api.internal;


import com.tyron.builder.api.Action;
import com.tyron.builder.internal.exceptions.Contextual;

public abstract class AbstractMutationGuard implements MutationGuard {
    @Override
    public void assertMutationAllowed(String methodName, Object target) {
        if (!isMutationAllowed()) {
            throw createIllegalStateException(Object.class, methodName, target);
        }
    }

    @Override
    public <T> void assertMutationAllowed(String methodName, T target, Class<T> targetType) {
        if (!isMutationAllowed()) {
            throw createIllegalStateException(targetType, methodName, target);
        }
    }

    private static <T> IllegalStateException createIllegalStateException(Class<T> targetType, String methodName, T target) {
        return new IllegalMutationException(String.format("%s#%s on %s cannot be executed in the current context.", targetType.getSimpleName(), methodName, target));
    }

    @Contextual
    private static class IllegalMutationException extends IllegalStateException {
        public IllegalMutationException(String message) {
            super(message);
        }
    }

    protected abstract <T> Action<? super T> newActionWithMutation(final Action<? super T> action, final boolean allowMutationMethods);

    @Override
    public <T> Action<? super T> withMutationEnabled(Action<? super T> action) {
        return newActionWithMutation(action, true);
    }

    @Override
    public <T> Action<? super T> withMutationDisabled(Action<? super T> action) {
        return newActionWithMutation(action, false);
    }
}