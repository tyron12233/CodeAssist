package com.tyron.builder.api.internal.project.taskfactory;

import com.tyron.builder.api.Task;

public interface TaskClassInfoStore {
    TaskClassInfo getTaskClassInfo(Class<? extends Task> type);
}
