package org.gradle.api.internal.tasks;

import org.gradle.api.tasks.TaskFilePropertyBuilder;

public interface TaskFilePropertyBuilderInternal extends TaskFilePropertyBuilder {

    @Override
    TaskFilePropertyBuilderInternal withPropertyName(String propertyName);
}