package com.tyron.builder.internal.event;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.dispatch.Dispatch;
import com.tyron.builder.internal.dispatch.MethodInvocation;
import com.tyron.builder.internal.dispatch.ProxyDispatchAdapter;

import java.util.Collection;

/**
 * <p>Manages a set of listeners of type T. Provides an implementation of T which can be used to broadcast to all
 * registered listeners.</p>
 *
 * <p>Ordering is maintained for events, so that events are delivered to listeners in the order they are generated.
 * Events are delivered to listeners in the order that listeners are added to this broadcaster.</p>
 *
 * <p>Implementations are not thread-safe.</p>
 *
 * @param <T> The listener type.
 */
public class ListenerBroadcast<T> implements Dispatch<MethodInvocation> {
    private ProxyDispatchAdapter<T> source;
    private BroadcastDispatch<T> broadcast;
    private final Class<T> type;

    public ListenerBroadcast(Class<T> type) {
        this.type = type;
        broadcast = BroadcastDispatch.empty(type);
    }

    /**
     * Returns the broadcaster. Any method call on this object is broadcast to all listeners.
     *
     * @return The broadcaster.
     */
    public T getSource() {
        if (source == null) {
            source = new ProxyDispatchAdapter<T>(this, type);
        }
        return source.getSource();
    }

    /**
     * Returns the type of listener to which this class broadcasts.
     *
     * @return The type of the broadcaster.
     */
    public Class<T> getType() {
        return type;
    }

    /**
     * Returns {@code true} if no listeners are registered with this object.
     *
     * @return {@code true} if no listeners are registered with this object, {@code false} otherwise
     */
    public boolean isEmpty() {
        return broadcast.isEmpty();
    }

    /**
     * Adds a listener.
     *
     * @param listener The listener.
     */
    public void add(T listener) {
        broadcast = broadcast.add(listener);
    }

    /**
     * Adds the given listeners.
     *
     * @param listeners The listeners
     */
    public void addAll(Collection<? extends T> listeners) {
        broadcast = broadcast.addAll(listeners);
    }

    /**
     * Adds a {@link Dispatch} to receive events from this broadcast.
     */
    public void add(Dispatch<MethodInvocation> dispatch) {
        broadcast = broadcast.add(dispatch);
    }

    /**
     * Adds an action to be executed when the given method is called.
     */
    public void add(String methodName, Action<?> action) {
        broadcast = broadcast.add(methodName, action);
    }

    /**
     * Removes the given listener.
     *
     * @param listener The listener.
     */
    public void remove(Object listener) {
        broadcast = broadcast.remove(listener);
    }

    /**
     * Removes the given listeners.
     *
     * @param listeners The listeners
     */
    public void removeAll(Collection<?> listeners) {
        broadcast = broadcast.removeAll(listeners);
    }

    /**
     * Removes all listeners.
     */
    public void removeAll() {
        broadcast = BroadcastDispatch.empty(type);
    }

    /**
     * Broadcasts the given event to all listeners.
     *
     * @param event The event
     */
    @Override
    public void dispatch(MethodInvocation event) {
        broadcast.dispatch(event);
    }

    public void visitListeners(Action<T> visitor) {
        broadcast.visitListeners(visitor);
    }

    /**
     * Returns a new {@link ListenerBroadcast} with the same {@link BroadcastDispatch} as this class.
     */
    public ListenerBroadcast<T> copy() {
        ListenerBroadcast<T> result = new ListenerBroadcast<T>(type);
        result.broadcast = this.broadcast;
        return result;
    }
}