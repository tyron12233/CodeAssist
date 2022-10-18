package org.gradle.tooling.internal.provider.runner;

import org.gradle.initialization.BuildEventConsumer;
import org.gradle.internal.operations.BuildOperationAncestryTracker;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.tooling.internal.protocol.events.InternalOperationFinishedProgressEvent;
import org.gradle.tooling.internal.protocol.events.InternalOperationStartedProgressEvent;
import org.gradle.tooling.internal.protocol.events.InternalProgressEvent;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class ProgressEventConsumer {

    private final Set<Object> startedIds = ConcurrentHashMap.newKeySet();
    private final BuildEventConsumer delegate;
    private final BuildOperationAncestryTracker ancestryTracker;

    ProgressEventConsumer(BuildEventConsumer delegate, BuildOperationAncestryTracker ancestryTracker) {
        this.delegate = delegate;
        this.ancestryTracker = ancestryTracker;
    }

    @Nullable
    OperationIdentifier findStartedParentId(BuildOperationDescriptor operation) {
        return ancestryTracker.findClosestMatchingAncestor(operation.getParentId(), startedIds::contains)
            .orElse(null);
    }

    void started(InternalOperationStartedProgressEvent event) {
        delegate.dispatch(event);
        startedIds.add(event.getDescriptor().getId());
    }

    void finished(InternalOperationFinishedProgressEvent event) {
        startedIds.remove(event.getDescriptor().getId());
        delegate.dispatch(event);
    }

    void progress(InternalProgressEvent event) {
        delegate.dispatch(event);
    }
}