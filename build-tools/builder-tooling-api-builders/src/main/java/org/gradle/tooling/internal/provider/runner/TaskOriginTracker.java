package org.gradle.tooling.internal.provider.runner;

import com.google.common.collect.ImmutableList;
import org.gradle.api.internal.project.taskfactory.TaskIdentity;
import org.gradle.api.internal.tasks.RealizeTaskBuildOperationType;
import org.gradle.api.internal.tasks.RegisterTaskBuildOperationType;
import org.gradle.internal.operations.BuildOperationDescriptor;
import org.gradle.internal.operations.OperationFinishEvent;
import org.gradle.internal.operations.OperationStartEvent;
import org.gradle.tooling.internal.protocol.events.InternalPluginIdentifier;
import org.gradle.tooling.internal.provider.runner.PluginApplicationTracker.PluginApplication;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class TaskOriginTracker implements BuildOperationTracker {

    private final Map<Long, InternalPluginIdentifier> origins = new ConcurrentHashMap<>();
    private final PluginApplicationTracker pluginApplicationTracker;

    TaskOriginTracker(PluginApplicationTracker pluginApplicationTracker) {
        this.pluginApplicationTracker = pluginApplicationTracker;
    }

    @Override
    public List<? extends BuildOperationTracker> getTrackers() {
        return ImmutableList.of(pluginApplicationTracker);
    }

    @Nullable
    InternalPluginIdentifier getOriginPlugin(TaskIdentity<?> taskIdentity) {
        return origins.get(taskIdentity.uniqueId);
    }

    @Override
    public void started(BuildOperationDescriptor buildOperation, OperationStartEvent startEvent) {
        if (buildOperation.getDetails() instanceof RealizeTaskBuildOperationType.Details) {
            RealizeTaskBuildOperationType.Details details = (RealizeTaskBuildOperationType.Details) buildOperation.getDetails();
            storeOrigin(buildOperation, details.getTaskId());
        } else if (buildOperation.getDetails() instanceof RegisterTaskBuildOperationType.Details) {
            RegisterTaskBuildOperationType.Details details = (RegisterTaskBuildOperationType.Details) buildOperation.getDetails();
            storeOrigin(buildOperation, details.getTaskId());
        }
    }

    private void storeOrigin(BuildOperationDescriptor buildOperation, long taskId) {
        origins.computeIfAbsent(taskId, key -> {
            PluginApplication pluginApplication = pluginApplicationTracker.findRunningPluginApplication(buildOperation.getParentId());
            return pluginApplication == null ? null : pluginApplication.getPlugin();
        });
    }

    @Override
    public void finished(BuildOperationDescriptor buildOperation, OperationFinishEvent finishEvent) {
        // origins have to be stored until the end of the build
    }

}