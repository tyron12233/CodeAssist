package org.gradle.api.internal.project.taskfactory;

import org.gradle.api.DefaultTask;
import org.gradle.api.Describable;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Task;
import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.reflect.ObjectInstantiationException;
import org.gradle.api.tasks.TaskInstantiationException;
import org.gradle.internal.Describables;
import org.gradle.internal.instantiation.InstantiationScheme;
import org.gradle.util.internal.NameValidator;

import javax.annotation.Nullable;
import java.util.concurrent.Callable;

public class TaskFactory implements ITaskFactory {
    private final ProjectInternal project;
    private final InstantiationScheme instantiationScheme;

    public TaskFactory() {
        this(null, null);
    }

    private TaskFactory(ProjectInternal project, InstantiationScheme instantiationScheme) {
        this.project = project;
        this.instantiationScheme = instantiationScheme;
    }

    @Override
    public ITaskFactory createChild(ProjectInternal project, InstantiationScheme instantiationScheme) {
        return new TaskFactory(project, instantiationScheme);
    }

    @Override
    @SuppressWarnings("deprecation")
    public <S extends Task> S create(final TaskIdentity<S> identity, @Nullable final Object[] constructorArgs) {
        if (!Task.class.isAssignableFrom(identity.type)) {
            throw new InvalidUserDataException(String.format(
                "Cannot create task '%s' of type '%s' as it does not implement the Task interface.",
                identity.identityPath.toString(),
                identity.type.getSimpleName()));
        }

        NameValidator.validate(identity.name, "task name", "");

        final Class<? extends DefaultTask> implType;
        if (identity.type == Task.class) {
            implType = DefaultTask.class;
        } else if (DefaultTask.class.isAssignableFrom(identity.type)) {
            implType = identity.type.asSubclass(DefaultTask.class);
        } else if (identity.type == org.gradle.api.internal.AbstractTask.class || identity.type == TaskInternal.class) {
            throw new InvalidUserDataException(String.format(
                "Cannot create task '%s' of type '%s' as this type is not supported for task registration.",
                identity.identityPath.toString(),
                identity.type.getSimpleName()));
        } else {
            throw new InvalidUserDataException(String.format(
                "Cannot create task '%s' of type '%s' as directly extending AbstractTask is not supported.",
                identity.identityPath.toString(),
                identity.type.getSimpleName()));
        }

        Describable displayName = Describables.withTypeAndName("task", identity.getIdentityPath());

        return org.gradle.api.internal.AbstractTask.injectIntoNewInstance(project, identity,
                () -> {
                    try {
                        Task instance;
                        if (constructorArgs != null) {
                            instance = instantiationScheme.instantiator().newInstanceWithDisplayName(implType, displayName, constructorArgs);
                        } else {
                            instance = instantiationScheme.deserializationInstantiator().newInstance(implType, org.gradle.api.internal.AbstractTask.class);
                        }
                        return identity.type.cast(instance);
                    } catch (ObjectInstantiationException e) {
                        throw new TaskInstantiationException(String.format("Could not create task of type '%s'.", identity.type.getSimpleName()),
                            e.getCause());
                    }
                });
    }
}
