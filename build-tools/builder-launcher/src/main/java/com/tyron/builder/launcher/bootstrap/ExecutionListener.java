package com.tyron.builder.launcher.bootstrap;

/**
 * Allows an execution action to provide status information to the execution context.
 *
 * <p>Note: if the action does not call {@link #onFailure(Throwable)}, then the execution is assumed to have
 * succeeded.</p>
 */
public interface ExecutionListener {
    /**
     * Reports a failure of the execution. Note that it is the caller's responsibility to perform any logging of the
     * failure.
     *
     * @param failure The execution failure. This exception has already been logged.
     */
    void onFailure(Throwable failure);
}
