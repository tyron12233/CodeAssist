package org.gradle.tooling.internal.provider.runner;

import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.OperationFinishEvent;
import org.gradle.internal.operations.OperationStartEvent;

import java.util.Collections;
import java.util.List;

/**
 * Tracks some state for build operations of a given type.
 */
public interface BuildOperationTracker {
    /**
     * Returns the trackers that are used by this tracker. If this tracker is required, then its trackers should be notified of
     * build operation execution. If this tracker is not required, the trackers can be ignored.
     */
    default List<? extends BuildOperationTracker> getTrackers() {
        return Collections.emptyList();
    }

    void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent);

    void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent);

    /**
     * Signals that the state for the given build operation is no longer required.
     */
    default void discardState(BuildOperationDescriptor buildOperation) {
    }
}