package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.Task;

public interface TaskResolver {
    Task resolveTask(String path);
}
