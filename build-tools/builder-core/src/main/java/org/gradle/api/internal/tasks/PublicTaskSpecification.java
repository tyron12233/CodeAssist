package org.gradle.api.internal.tasks;

import com.google.common.base.Strings;
import org.gradle.api.Task;
import org.gradle.api.specs.Spec;

/**
 * Decides whether a {@link org.gradle.api.Task} is a public task or not.
 */
public final class PublicTaskSpecification implements Spec<Task> {

    public static final Spec<Task> INSTANCE = new PublicTaskSpecification();

    private PublicTaskSpecification() {
    }

    @Override
    public boolean isSatisfiedBy(Task task) {
        return !Strings.isNullOrEmpty(task.getGroup());
    }

}