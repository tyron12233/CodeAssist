package org.gradle.api.internal.tasks.execution;

import org.gradle.api.internal.TaskInternal;
import org.gradle.api.internal.tasks.properties.TaskProperties;
import org.gradle.internal.execution.caching.CachingDisabledReason;
import org.gradle.internal.execution.history.OverlappingOutputs;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;

public interface TaskCacheabilityResolver {
    Optional<CachingDisabledReason> shouldDisableCaching(
        TaskInternal task,
        TaskProperties taskProperties,
        Collection<SelfDescribingSpec<TaskInternal>> cacheIfSpecs,
        Collection<SelfDescribingSpec<TaskInternal>> doNotCacheIfSpecs,
        @Nullable OverlappingOutputs overlappingOutputs
    );
}
