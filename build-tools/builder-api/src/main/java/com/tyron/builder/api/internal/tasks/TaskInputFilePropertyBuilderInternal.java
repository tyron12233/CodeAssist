package com.tyron.builder.api.internal.tasks;

import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.api.tasks.PathSensitivity;
import com.tyron.builder.api.tasks.TaskInputFilePropertyBuilder;

public interface TaskInputFilePropertyBuilderInternal extends TaskInputFilePropertyBuilder, TaskFilePropertyBuilderInternal {

    @Override
    TaskInputFilePropertyBuilderInternal withNormalizer(Class<? extends FileNormalizer> normalizer);

    @Override
    TaskInputFilePropertyBuilderInternal withPropertyName(String propertyName);

    @Override
    TaskInputFilePropertyBuilderInternal withPathSensitivity(PathSensitivity sensitivity);

    @Override
    TaskInputFilePropertyBuilderInternal skipWhenEmpty();

    @Override
    TaskInputFilePropertyBuilderInternal skipWhenEmpty(boolean skipWhenEmpty);

    @Override
    TaskInputFilePropertyBuilderInternal optional();

    @Override
    TaskInputFilePropertyBuilderInternal optional(boolean optional);
}