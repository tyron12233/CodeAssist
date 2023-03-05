package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Task;

public interface TaskClassInfoStore {
    TaskClassInfo getTaskClassInfo(Class<? extends Task> type);
}
