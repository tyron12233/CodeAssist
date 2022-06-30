package com.tyron.builder.configuration.internal;

import com.tyron.builder.BuildListener;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.ProjectEvaluationListener;
import com.tyron.builder.api.execution.TaskExecutionGraphListener;
import com.tyron.builder.internal.InternalListener;

import groovy.lang.Closure;

/**
 * Decorates listener functions/objects to fire {@link ExecuteListenerBuildOperationType} build operations when later executed.
 *
 * Works in conjunction with {@link UserCodeApplicationContext} to attach the current user code application ID
 * to the listener, in order to convey it as part of the operation details.
 * This allows tracking the listener back to the plugin or script that <i>registered</i> it.
 */
public interface ListenerBuildOperationDecorator {

    /**
     * Decorates an action listener.
     * <p>
     * Does not decorate any action that implements {@link InternalListener}.
     * Does not decorate if there is not currently a script or plugin being applied on the thread.
     *
     * @param registrationPoint the place that the listener was registered - used in the operation description / details
     * @param action the action to decorate
     */
    <T> Action<T> decorate(String registrationPoint, Action<T> action);

    /**
     * Decorates a closure listener.
     * <p>
     * Does not decorate any action that implements {@link InternalListener}.
     * Does not decorate if there is not currently a script or plugin being applied on the thread.
     *
     * @param registrationPoint the place that the listener was registered - used in the operation description / details
     * @param closure the closure to decorate
     */
    <T> Closure<T> decorate(String registrationPoint, Closure<T> closure);

    /**
     * Decorates a listener type object.
     * <p>
     * Supports decorating {@link BuildListener}, {@link ProjectEvaluationListener} and {@link TaskExecutionGraphListener} listeners
     * <p>
     * Does not decorate any action that implements {@link InternalListener}.
     * Does not decorate if there is not currently a script or plugin being applied on the thread.
     *
     * @param cls the type of the listener
     * @param registrationPoint the place that the listener was registered - used in the operation description / details
     * @param listener the listener
     */
    <T> T decorate(String registrationPoint, Class<T> cls, T listener);

    /**
     * Decorates a listener of unknown type.
     * <p>
     * @param registrationPoint the place that the listener was registered - used in the operation description / details
     * @param listener the listener object to decorate
     * @see #decorate(String, Class, Object)
     */
    Object decorateUnknownListener(String registrationPoint, Object listener);


}
