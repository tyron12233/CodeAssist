package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.tasks.TaskFilePropertyBuilder;

public interface TaskFilePropertyBuilderInternal extends TaskFilePropertyBuilder {

    @Override
    TaskFilePropertyBuilderInternal withPropertyName(String propertyName);
}