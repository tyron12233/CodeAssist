package org.gradle.api.internal;

import org.gradle.api.Action;

/**
 * A guard object for the mutability of an object. All mutable method of the object needs to be guarded by calling {@code #assertMutationAllowed(String)}. If you want to allow ad-hoc code to pass over the mutation guard of the object, the object will need to implement {@code WithMutationGuard}. You can then use {@code MutationGuards#of(Object)} to acquire the guard and enable/disable the mutation as you see fit.
 */
public interface MutationGuard {
    /**
     * Wraps the specified action with mutation disabling code.
     *
     * @param action the action to disable mutation during execution.
     * @param <T> the action parameter type
     * @return an action
     */
    <T> Action<? super T> withMutationDisabled(Action<? super T> action);

    /**
     * Wraps the specified action with mutation enabling code.
     *
     * @param action the action to enable mutation during execution.
     * @param <T> the action parameter type
     * @return an action
     */
    <T> Action<? super T> withMutationEnabled(Action<? super T> action);

    /**
     * Returns {@code true} if the mutation is enabled, and {@code false} otherwise.
     */
    boolean isMutationAllowed();

    /**
     * Throws exception when mutation is not allowed.
     *
     * @param methodName the method name the assertion is testing
     * @param target the target object been asserted on
     */
    void assertMutationAllowed(String methodName, Object target);

    <T> void assertMutationAllowed(String methodName, T target, Class<T> targetType);
}