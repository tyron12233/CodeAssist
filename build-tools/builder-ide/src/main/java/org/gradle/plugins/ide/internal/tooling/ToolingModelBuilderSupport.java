package org.gradle.plugins.ide.internal.tooling;

import org.gradle.api.Task;
import org.gradle.api.internal.tasks.PublicTaskSpecification;
import org.gradle.plugins.ide.internal.tooling.model.LaunchableGradleTask;
import org.gradle.tooling.internal.gradle.DefaultProjectIdentifier;

public abstract class ToolingModelBuilderSupport {
    public static <T extends LaunchableGradleTask> T buildFromTask(T target, DefaultProjectIdentifier projectIdentifier, Task task) {
        target.setPath(task.getPath())
                .setName(task.getName())
                .setGroup(task.getGroup())
                .setDisplayName(task.toString())
                .setDescription(task.getDescription())
                .setPublic(PublicTaskSpecification.INSTANCE.isSatisfiedBy(task))
                .setProjectIdentifier(projectIdentifier);
        return target;
    }
}