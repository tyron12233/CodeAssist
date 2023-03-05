package org.gradle.api.internal.changedetection;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.properties.TaskProperties;

public interface TaskExecutionModeResolver {
    TaskExecutionMode getExecutionMode(TaskInternal task, TaskProperties properties);
}