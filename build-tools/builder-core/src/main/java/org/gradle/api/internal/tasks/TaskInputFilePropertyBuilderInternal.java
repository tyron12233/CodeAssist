package org.gradle.api.internal.tasks;

import org.gradle.api.tasks.FileNormalizer;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskInputFilePropertyBuilder;

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