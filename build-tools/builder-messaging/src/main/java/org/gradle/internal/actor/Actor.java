package org.gradle.internal.actor;

import org.gradle.internal.concurrent.Stoppable;
import org.gradle.internal.concurrent.ThreadSafe;
import org.gradle.internal.dispatch.Dispatch;
import org.gradle.internal.dispatch.DispatchException;
import org.gradle.internal.dispatch.MethodInvocation;

/**
 * <p>An {@code Actor} dispatches method calls to a target object in a thread-safe manner. Methods are called either by
 * calling {@link Dispatch#dispatch(Object)} on the actor, or using the proxy object
 * returned by {@link #getProxy(Class)}. Methods are delivered to the target object in the order they are called on the
 * actor, but are delivered to the target object by a single thread at a time. In this way, the target object does not need
 * to perform any synchronisation.</p>
 *
 * <p>An actor uses one of two modes to deliver method calls to the target object:</p>
 *
 * <ul>
 * <li>Non-blocking, or asynchronous, so that method dispatch does not block waiting for the method call to be delivered or executed.
 * In this mode, the method return value or exception is not delivered back to the dispatcher.
 * </li>
 *
 * <li>Blocking, or synchronous, so that method dispatch blocks until the method call has been delivered and executed. In this mode, the
 * method return value or exception is delivered back to the dispatcher.
 * </li>
 *
 * </ul>
 *
 * <p>All implementations of this interface must be thread-safe.</p>
 */
public interface Actor extends Dispatch<MethodInvocation>, Stoppable, ThreadSafe {
    /**
     * Creates a proxy which delivers method calls to the target object.
     *
     * @param type the type for the proxy.
     * @return The proxy.
     */
    <T> T getProxy(Class<T> type);

    /**
     * Stops accepting new method calls, and blocks until all method calls have been executed by the target object.
     *
     * @throws DispatchException When there were any failures dispatching method calls to the target object.
     */
    @Override
    void stop() throws DispatchException;
}
