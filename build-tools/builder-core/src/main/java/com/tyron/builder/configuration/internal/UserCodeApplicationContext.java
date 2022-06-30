package com.tyron.builder.configuration.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Assigns and stores an ID for the application of some user code (e.g. scripts and plugins).
 */
@ServiceScope(Scopes.BuildSession.class)
public interface UserCodeApplicationContext {
    /**
     * Applies some user code, assigning an ID for this particular application.
     *
     * @param displayName A display name for the user code.
     * @param action The action to run to apply the user code.
     */
    void apply(DisplayName displayName, Action<? super UserCodeApplicationId> action);

    /**
     * Runs some Gradle runtime code.
     */
    void gradleRuntime(Runnable runnable);

    /**
     * Returns an action that represents some deferred execution of the current user code. While the returned action is running, the details of the current application are restored.
     * Returns the given action when there is no current application.
     */
    <T> Action<T> reapplyCurrentLater(Action<T> action);

    /**
     * Returns details of the current application, if any.
     */
    @Nullable
    Application current();

    /**
     * Immutable representation of the application of some user code.
     */
    interface Application {
        UserCodeApplicationId getId();

        /**
         * Returns the display name of the user code.
         */
        DisplayName getDisplayName();

        /**
         * Returns an action that represents some deferred execution of the user code. While the returned action is running, the details of this application are restored.
         */
        <T> Action<T> reapplyLater(Action<T> action);

        /**
         * Runs an action that represents some deferred execution of the user code. While the action is running, the details of this application are restored.
         */
        void reapply(Runnable runnable);

        /**
         * Runs an action that represents some deferred execution of the user code. While the action is running, the details of this application are restored.
         */
        <T> T reapply(Supplier<T> action);
    }
}
