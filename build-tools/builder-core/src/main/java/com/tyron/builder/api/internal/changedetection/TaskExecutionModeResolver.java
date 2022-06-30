package com.tyron.builder.api.internal.changedetection;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;

public interface TaskExecutionModeResolver {
    TaskExecutionMode getExecutionMode(TaskInternal task, TaskProperties properties);
}