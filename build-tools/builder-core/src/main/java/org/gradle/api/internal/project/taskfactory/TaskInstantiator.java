package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Task;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.model.internal.core.NamedEntityInstantiator;

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
