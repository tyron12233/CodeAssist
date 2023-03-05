package org.gradle.api.internal.artifacts.publish;

import com.google.common.collect.ImmutableSet;
import org.gradle.api.internal.artifacts.PublishArtifactInternal;
import org.gradle.api.internal.tasks.DefaultTaskDependency;
import org.gradle.api.internal.tasks.TaskResolver;
import org.gradle.api.tasks.TaskDependency;

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
