package org.gradle.internal.build.event.types;

import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.OperationIdentifier;
import org.gradle.tooling.internal.protocol.events.InternalBuildPhaseDescriptor;

public class DefaultBuildPhaseDescriptor extends DefaultOperationDescriptor implements InternalBuildPhaseDescriptor {
    private final String buildPhase;
    private final int workItemCount;

    public DefaultBuildPhaseDescriptor(BuildOperationDescriptor buildOperation, OperationIdentifier parentId, String buildPhase, int workItemCount) {
        super(
            buildOperation.getId(),
            buildOperation.getName(),
            buildOperation.getDisplayName(),
            parentId
        );
        this.buildPhase = buildPhase;
        this.workItemCount = workItemCount;
    }

    public DefaultBuildPhaseDescriptor(OperationIdentifier id, String name, String displayName, OperationIdentifier parentId, String buildPhase, int workItemCount) {
        super(id, name, displayName, parentId);
        this.buildPhase = buildPhase;
        this.workItemCount = workItemCount;
    }

    @Override
    public String getBuildPhase() {
        return buildPhase;
    }

    @Override
    public int getBuildItemsCount() {
        return workItemCount;
    }
}