package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.providers.NamedDomainObjectProvider;

/**
 * Providers a task of the given type.
 *
 * @param <T> Task type
 * @since 4.8
 */
public interface TaskProvider<T extends Task> extends NamedDomainObjectProvider<T> {
    /**
     * Configures the task with the given action. Actions are run in the order added.
     *
     * @param action A {@link Action} that can configure the task when required.
     * @since 4.8
     */
    @Override
    void configure(Action<? super T> action);

    /**
     * The task name referenced by this provider.
     * <p>
     * Must be constant for the life of the object.
     *
     * @return The task name. Never null.
     * @since 4.9
     */
    @Override
    String getName();
}
