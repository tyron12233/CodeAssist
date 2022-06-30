package com.tyron.builder.api.internal.project.taskfactory;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.instantiation.InstantiationScheme;

import javax.annotation.Nullable;

public interface ITaskFactory {
    ITaskFactory createChild(ProjectInternal project, InstantiationScheme instantiationScheme);

    /**
     * @param constructorArgs null == do not invoke constructor, empty == invoke constructor with no args, non-empty = invoke constructor with args
     */
    <S extends Task> S create(TaskIdentity<S> taskIdentity, @Nullable Object[] constructorArgs);
}
