package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;

public class MutationGuards {
    private static final MutationGuard IDENTITY_MUTATION_GUARD = new MutationGuard() {
        @Override
        public <T> Action<? super T> withMutationDisabled(Action<? super T> action) {
            return action;
        }

        @Override
        public <T> Action<? super T> withMutationEnabled(Action<? super T> action) {
            return action;
        }

        @Override
        public boolean isMutationAllowed() {
            return true;
        }

        @Override
        public void assertMutationAllowed(String methodName, Object target) {
            // do nothing
        }

        @Override
        public <T> void assertMutationAllowed(String methodName, T target, Class<T> targetType) {
            // do nothing
        }
    };

    /**
     * Returns the identity mutation guard. It protect and do nothing.
     */
    public static MutationGuard identity() {
        return IDENTITY_MUTATION_GUARD;
    }

    /**
     * Retrieves the {@code MutationGuard} of the target if it implements {@code WithMutationGuard}, else returns an identity mutation guard performing no guard operations.
     */
    public static MutationGuard of(Object target) {
        if (target instanceof WithMutationGuard) {
            return ((WithMutationGuard) target).getMutationGuard();
        } else {
            return IDENTITY_MUTATION_GUARD;
        }
    }
}
