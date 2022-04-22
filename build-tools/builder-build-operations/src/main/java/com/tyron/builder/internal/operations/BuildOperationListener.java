package com.tyron.builder.internal.operations;

import com.tyron.builder.internal.service.scopes.EventScope;
import com.tyron.builder.internal.service.scopes.Scope;

/**
 * A listener that is notified as build operations are executed.
 *
 * Listeners must be registered via {@link BuildOperationListenerManager}, not ListenerManager.
 *
 * Unlike ListenerManager bound listeners, build operation listener signalling is not synchronized.
 * Implementations must take care to be threadsafe.
 *
 * Related signals are guaranteed to be serialized.
 * That is, a listener will not concurrently be notified of the same operation starting and finishing.
 *
 * @since 3.5
 */
@EventScope(Scope.Global.class)
public interface BuildOperationListener {

    void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent);

    void progress(OperationIdentifier operationIdentifier, OperationProgressEvent progressEvent);

    void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent);

}