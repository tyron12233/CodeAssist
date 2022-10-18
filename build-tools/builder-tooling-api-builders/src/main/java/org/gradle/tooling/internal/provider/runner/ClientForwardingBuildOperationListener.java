package org.gradle.tooling.internal.provider.runner;

import org.gradle.internal.build.event.types.AbstractOperationResult;
import org.gradle.internal.build.event.types.DefaultFailure;
import org.gradle.internal.build.event.types.DefaultFailureResult;
import org.gradle.internal.build.event.types.DefaultOperationDescriptor;
import org.gradle.internal.build.event.types.DefaultOperationFinishedProgressEvent;
import org.gradle.internal.build.event.types.DefaultOperationStartedProgressEvent;
import org.gradle.internal.build.event.types.DefaultSuccessResult;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.BuildOperationListener;
import org.gradle.internal.operations.OperationFinishEvent;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.operations.OperationProgressEvent;
import org.gradle.internal.operations.OperationStartEvent;

import java.util.Collections;

/**
 * Build listener that forwards all receiving events to the client via the provided {@code ProgressEventConsumer} instance.
 *
 * @since 2.5
 */
class ClientForwardingBuildOperationListener implements BuildOperationListener {

    private final ProgressEventConsumer eventConsumer;

    ClientForwardingBuildOperationListener(ProgressEventConsumer eventConsumer) {
        this.eventConsumer = eventConsumer;
    }

    @Override
    public void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
        eventConsumer.started(new DefaultOperationStartedProgressEvent(startEvent.getStartTime(), toBuildOperationDescriptor(buildOperation)));
    }

    @Override
    public void progress(OperationIdentifier buildOperationId, OperationProgressEvent progressEvent) {
    }

    @Override
    public void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent result) {
        eventConsumer.finished(new DefaultOperationFinishedProgressEvent(result.getEndTime(), toBuildOperationDescriptor(buildOperation), toOperationResult(result)));
    }

    private DefaultOperationDescriptor toBuildOperationDescriptor(BuildOperationDescriptor buildOperation) {
        OperationIdentifier id = buildOperation.getId();
        String name = buildOperation.getName();
        String displayName = buildOperation.getDisplayName();
        OperationIdentifier parentId = eventConsumer.findStartedParentId(buildOperation);
        return new DefaultOperationDescriptor(id, name, displayName, parentId);
    }

    static AbstractOperationResult toOperationResult(OperationFinishEvent result) {
        Throwable failure = result.getFailure();
        long startTime = result.getStartTime();
        long endTime = result.getEndTime();
        if (failure != null) {
            return new DefaultFailureResult(startTime, endTime, Collections.singletonList(DefaultFailure.fromThrowable(failure)));
        }
        return new DefaultSuccessResult(startTime, endTime);
    }
}