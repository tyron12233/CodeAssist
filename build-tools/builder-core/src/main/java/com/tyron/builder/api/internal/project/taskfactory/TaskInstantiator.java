package com.tyron.builder.api.internal.project.taskfactory;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.model.internal.core.NamedEntityInstantiator;

public class TaskInstantiator implements NamedEntityInstantiator<Task> {
    private static final Object[] NO_PARAMS = new Object[0];
    private final ITaskFactory taskFactory;
    private final ProjectInternal project;

    public TaskInstantiator(ITaskFactory taskFactory, ProjectInternal project) {
        this.taskFactory = taskFactory;
        this.project = project;
    }

    @Override
    public <S extends Task> S create(String name, Class<S> type) {
        return taskFactory.create(TaskIdentity.create(name, type, project), NO_PARAMS);
    }
}
