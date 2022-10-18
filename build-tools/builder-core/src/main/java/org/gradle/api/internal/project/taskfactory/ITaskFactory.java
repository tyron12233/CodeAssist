package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.Task;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.internal.instantiation.InstantiationScheme;

import javax.annotation.Nullable;

public interface ITaskFactory {
    ITaskFactory createChild(ProjectInternal project, InstantiationScheme instantiationScheme);

    /**
     * @param constructorArgs null == do not invoke constructor, empty == invoke constructor with no args, non-empty = invoke constructor with args
     */
    <S extends Task> S create(TaskIdentity<S> taskIdentity, @Nullable Object[] constructorArgs);
}
