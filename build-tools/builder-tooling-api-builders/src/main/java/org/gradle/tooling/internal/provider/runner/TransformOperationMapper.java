package org.gradle.tooling.internal.provider.runner;

import org.gradle.api.internal.artifacts.transform.ExecuteScheduledTransformationStepBuildOperationDetails;
import org.gradle.api.internal.artifacts.transform.TransformationNode;
import org.gradle.execution.plan.Node;
import org.gradle.internal.build.event.BuildEventSubscriptions;
import org.gradle.internal.build.event.types.DefaultOperationFinishedProgressEvent;
import org.gradle.internal.build.event.types.DefaultOperationStartedProgressEvent;
import org.gradle.internal.build.event.types.DefaultTransformDescriptor;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.OperationFinishEvent;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.internal.operations.OperationStartEvent;
import org.gradle.tooling.events.OperationType;
import org.gradle.tooling.internal.protocol.events.InternalOperationDescriptor;
import org.gradle.tooling.internal.protocol.events.InternalOperationFinishedProgressEvent;
import org.gradle.tooling.internal.protocol.events.InternalOperationStartedProgressEvent;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.gradle.tooling.internal.provider.runner.ClientForwardingBuildOperationListener.toOperationResult;

/**
 * Transform listener that forwards all receiving events to the client via the provided {@code ProgressEventConsumer} instance.
 *
 * @since 5.1
 */
class TransformOperationMapper implements BuildOperationMapper<ExecuteScheduledTransformationStepBuildOperationDetails, DefaultTransformDescriptor>, OperationDependencyLookup {
    private final Map<TransformationNode, DefaultTransformDescriptor> descriptors = new ConcurrentHashMap<>();
    private final OperationDependenciesResolver operationDependenciesResolver;

    TransformOperationMapper(OperationDependenciesResolver operationDependenciesResolver) {
        this.operationDependenciesResolver = operationDependenciesResolver;
    }

    @Override
    public boolean isEnabled(BuildEventSubscriptions subscriptions) {
        return subscriptions.isRequested(OperationType.TRANSFORM);
    }

    @Override
    public Class<ExecuteScheduledTransformationStepBuildOperationDetails> getDetailsType() {
        return ExecuteScheduledTransformationStepBuildOperationDetails.class;
    }

    @Override
    public InternalOperationDescriptor lookupExistingOperationDescriptor(Node node) {
        if (node instanceof TransformationNode) {
            return descriptors.get(node);
        }
        return null;
    }

    @Override
    public DefaultTransformDescriptor createDescriptor(ExecuteScheduledTransformationStepBuildOperationDetails details, BuildOperationDescriptor buildOperation, @Nullable OperationIdentifier parent) {
        OperationIdentifier id = buildOperation.getId();
        String displayName = buildOperation.getDisplayName();
        String transformerName = details.getTransformerName();
        String subjectName = details.getSubjectName();
        Set<InternalOperationDescriptor> dependencies = operationDependenciesResolver.resolveDependencies(details.getTransformationNode());
        DefaultTransformDescriptor descriptor = new DefaultTransformDescriptor(id, displayName, parent, transformerName, subjectName, dependencies);
        descriptors.put(details.getTransformationNode(), descriptor);
        return descriptor;
    }

    @Override
    public InternalOperationStartedProgressEvent createStartedEvent(DefaultTransformDescriptor descriptor, ExecuteScheduledTransformationStepBuildOperationDetails executeScheduledTransformationStepBuildOperationDetails, OperationStartEvent startEvent) {
        return new DefaultOperationStartedProgressEvent(startEvent.getStartTime(), descriptor);
    }

    @Override
    public InternalOperationFinishedProgressEvent createFinishedEvent(DefaultTransformDescriptor descriptor, ExecuteScheduledTransformationStepBuildOperationDetails executeScheduledTransformationStepBuildOperationDetails, OperationFinishEvent finishEvent) {
        return new DefaultOperationFinishedProgressEvent(finishEvent.getEndTime(), descriptor, toOperationResult(finishEvent));
    }
}
