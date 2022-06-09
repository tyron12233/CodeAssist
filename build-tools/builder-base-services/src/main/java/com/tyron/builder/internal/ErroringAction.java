package com.tyron.builder.internal;

import com.tyron.builder.api.Action;

/**
 * Action adapter/implementation for action code that may throw exceptions.
 *
 * Implementations implement doExecute() (instead of execute()) which is allowed to throw checked exceptions.
 * Any checked exceptions thrown will be wrapped as unchecked exceptions and re-thrown.
 *
 * How the exception is wrapped is subject to {@link UncheckedException#throwAsUncheckedException(Throwable)}.
 *
 * @param <T> The type of object which this action accepts.
 */
public abstract class ErroringAction<T> implements Action<T> {

    @Override
    public void execute(T thing) {
        try {
            doExecute(thing);
        } catch (Exception e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }
    }

    protected abstract void doExecute(T thing) throws Exception;

}
