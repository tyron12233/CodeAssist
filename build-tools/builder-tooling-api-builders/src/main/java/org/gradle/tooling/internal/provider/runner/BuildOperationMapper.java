package org.gradle.tooling.internal.provider.runner;

import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.OperationFinishEvent;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.operations.OperationProgressEvent;
import org.gradle.internal.operations.OperationStartEvent;
import org.gradle.tooling.internal.protocol.events.InternalOperationDescriptor;
import org.gradle.tooling.internal.protocol.events.InternalOperationFinishedProgressEvent;
import org.gradle.tooling.internal.protocol.events.InternalOperationStartedProgressEvent;
import org.gradle.tooling.internal.protocol.events.InternalProgressEvent;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * Maps build operations of a particular type into progress events to forward to the tooling API client.
 */
public interface BuildOperationMapper<DETAILS, TO extends InternalOperationDescriptor> {
    boolean isEnabled(BuildEventSubscriptions subscriptions);

    Class<DETAILS> getDetailsType();

    /**
     * Returns the trackers that are used by this mapper. If this mapper is enabled, then the trackers should be notified of
     * build operation execution. If this mapper is not enabled, the trackers can be ignored.
     */
    default List<? extends BuildOperationTracker> getTrackers() {
        return Collections.emptyList();
    }

    /**
     * Maps the descriptor for the given build operation.
     */
    TO createDescriptor(DETAILS details, BuildOperationDescriptor buildOperation, @Nullable OperationIdentifier parent);

    /**
     * Maps the start event for the given build operation.
     */
    InternalOperationStartedProgressEvent createStartedEvent(TO descriptor, DETAILS details, OperationStartEvent startEvent);

    /**
     * Maps the finish event for the given build operation.
     */
    InternalOperationFinishedProgressEvent createFinishedEvent(TO descriptor, DETAILS details, OperationFinishEvent finishEvent);

    /**
     * Maps the given progress event. Returns {@code null} of the event should be discarded and not forwarded to the client.
     */
    @Nullable
    default InternalProgressEvent createProgressEvent(TO descriptor, OperationProgressEvent progressEvent) {
        return null;
    }
}