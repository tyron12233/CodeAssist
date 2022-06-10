package org.gradle.api.internal.tasks.execution;

import com.google.common.collect.ImmutableMap;
import org.gradle.execution.plan.LocalTaskNode;
import org.gradle.api.internal.TaskInternal;
import org.gradle.internal.operations.trace.CustomOperationTraceSerialization;
import org.gradle.api.internal.project.taskfactory.TaskIdentity;

public class ExecuteTaskBuildOperationDetails implements ExecuteTaskBuildOperationType.Details, CustomOperationTraceSerialization {

    private final LocalTaskNode taskNode;

    public ExecuteTaskBuildOperationDetails(LocalTaskNode taskNode) {
        this.taskNode = taskNode;
    }

    // TODO: do not reference mutable state
//    @NotUsedByScanPlugin
    public TaskInternal getTask() {
        return taskNode.getTask();
    }

//    @NotUsedByScanPlugin
    public LocalTaskNode getTaskNode() {
        return taskNode;
    }

    @Override
    public String getBuildPath() {
        return taskIdentity().buildPath.toString();
    }

    @Override
    public String getTaskPath() {
        return taskIdentity().projectPath.toString();
    }

    @Override
    public long getTaskId() {
        return taskIdentity().uniqueId;
    }

    @Override
    public Class<?> getTaskClass() {
        return taskIdentity().type;
    }

    @Override
    public Object getCustomOperationTraceSerializableModel() {
        ImmutableMap.Builder<String, Object> builder = new ImmutableMap.Builder<String, Object>();
        builder.put("buildPath", getBuildPath());
        builder.put("taskPath", getTaskPath());
        builder.put("taskClass", getTaskClass().getName());
        builder.put("taskId", getTaskId());
        return builder.build();
    }

    private TaskIdentity<?> taskIdentity() {
        return getTask().getTaskIdentity();
    }
}