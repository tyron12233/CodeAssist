package com.tyron.builder.api.project;

import com.tyron.builder.api.internal.tasks.TaskContainerInternal;

public interface BuildProject {

    TaskContainerInternal getTaskContainer();
}
