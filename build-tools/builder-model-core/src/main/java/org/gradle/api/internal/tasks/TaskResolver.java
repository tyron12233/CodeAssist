package org.gradle.api.internal.tasks;

import org.gradle.api.Task;

public interface TaskResolver {
    Task resolveTask(String path);
}
