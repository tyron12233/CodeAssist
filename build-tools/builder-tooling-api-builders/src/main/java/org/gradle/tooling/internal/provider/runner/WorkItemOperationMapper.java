package org.gradle.tooling.internal.provider.runner;

import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.internal.build.event.types.DefaultOperationFinishedProgressEvent;
import org.gradle.internal.build.event.types.DefaultOperationStartedProgressEvent;
import org.gradle.internal.build.event.types.DefaultWorkItemDescriptor;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.OperationFinishEvent;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.operations.OperationStartEvent;
import org.gradle.tooling.events.OperationType;
import org.gradle.tooling.internal.protocol.events.InternalOperationFinishedProgressEvent;
import org.gradle.tooling.internal.protocol.events.InternalOperationStartedProgressEvent;
import org.gradle.workers.internal.ExecuteWorkItemBuildOperationType;

import javax.annotation.Nullable;

import static org.gradle.tooling.internal.provider.runner.ClientForwardingBuildOperationListener.toOperationResult;

/**
 * Work item listener that forwards all receiving events to the client via the provided {@code ProgressEventConsumer} instance.
 *
 * @since 5.1
 */
class WorkItemOperationMapper implements BuildOperationMapper<ExecuteWorkItemBuildOperationType.Details, DefaultWorkItemDescriptor> {
    @Override
    public boolean isEnabled(BuildEventSubscriptions subscriptions) {
        return subscriptions.isRequested(OperationType.TASK) && subscriptions.isRequested(OperationType.WORK_ITEM);
    }

    @Override
    public Class<ExecuteWorkItemBuildOperationType.Details> getDetailsType() {
        return ExecuteWorkItemBuildOperationType.Details.class;
    }

    @Override
    public DefaultWorkItemDescriptor createDescriptor(ExecuteWorkItemBuildOperationType.Details details, BuildOperationDescriptor buildOperation, @Nullable OperationIdentifier parent) {
        OperationIdentifier id = buildOperation.getId();
        String className = details.getClassName();
        String displayName = buildOperation.getDisplayName();
        return new DefaultWorkItemDescriptor(id, className, displayName, parent);
    }

    @Override
    public InternalOperationStartedProgressEvent createStartedEvent(DefaultWorkItemDescriptor descriptor, ExecuteWorkItemBuildOperationType.Details details, OperationStartEvent startEvent) {
        return new DefaultOperationStartedProgressEvent(startEvent.getStartTime(), descriptor);
    }

    @Override
    public InternalOperationFinishedProgressEvent createFinishedEvent(DefaultWorkItemDescriptor descriptor, ExecuteWorkItemBuildOperationType.Details details, OperationFinishEvent finishEvent) {
        return new DefaultOperationFinishedProgressEvent(finishEvent.getEndTime(), descriptor, toOperationResult(finishEvent));
    }
}