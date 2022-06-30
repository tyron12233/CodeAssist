package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;
import com.tyron.builder.internal.execution.caching.CachingDisabledReason;
import com.tyron.builder.internal.execution.history.OverlappingOutputs;

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
