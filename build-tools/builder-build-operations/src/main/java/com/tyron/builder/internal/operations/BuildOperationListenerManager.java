package com.tyron.builder.internal.operations;

/**
 * Manages listeners of build operations.
 *
 * There is one global listener for the life of the build runtime.
 * Listeners must be sure to remove themselves if they want to only listen for a single build.
 *
 * Listeners are notified in registration order.
 * Started and progress notifications are emitted in registration order,
 * while finished notifications are emitted in reverse registration order.
 *
 * Listeners will not receive progress notifications for events before they have received
 * the corresponding start notification or after they have received the corresponding finished notification.
 * Such notifications are just discarded for the listener.
 *
 * @since 3.5
 */
public interface BuildOperationListenerManager {

    void addListener(BuildOperationListener listener);

    void removeListener(BuildOperationListener listener);

    BuildOperationListener getBroadcaster();

}