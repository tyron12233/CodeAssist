package com.tyron.builder.api.internal.artifacts.publish;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.internal.artifacts.PublishArtifactInternal;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependency;
import com.tyron.builder.api.internal.tasks.TaskResolver;
import com.tyron.builder.api.tasks.TaskDependency;

import javax.annotation.Nullable;

public abstract class AbstractPublishArtifact implements PublishArtifactInternal {
    private final DefaultTaskDependency taskDependency;

    public AbstractPublishArtifact(@Nullable TaskResolver resolver, Object... tasks) {
        taskDependency = new DefaultTaskDependency(resolver, ImmutableSet.copyOf(tasks));
    }

    public AbstractPublishArtifact(Object... tasks) {
        this(null, tasks);
    }

    @Override
    public TaskDependency getBuildDependencies() {
        return taskDependency;
    }

    public AbstractPublishArtifact builtBy(Object... tasks) {
        taskDependency.add(tasks);
        return this;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + " " + getName() + ":" + getType() + ":" +getExtension()  + ":" + getClassifier();
    }
}
