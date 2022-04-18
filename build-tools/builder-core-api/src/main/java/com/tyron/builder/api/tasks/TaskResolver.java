package com.tyron.builder.api.tasks;

import com.tyron.builder.api.Task;

public interface TaskResolver {
    Task resolveTask(String path);
}
