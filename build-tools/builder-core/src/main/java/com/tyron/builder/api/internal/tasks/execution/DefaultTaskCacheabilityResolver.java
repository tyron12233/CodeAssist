package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.api.internal.tasks.properties.CacheableOutputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;
import com.tyron.builder.internal.execution.caching.CachingDisabledReason;
import com.tyron.builder.internal.execution.caching.CachingDisabledReasonCategory;
import com.tyron.builder.internal.execution.history.OverlappingOutputs;
import com.tyron.builder.internal.file.RelativeFilePathResolver;
import com.tyron.builder.work.DisableCachingByDefault;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;

public class DefaultTaskCacheabilityResolver implements TaskCacheabilityResolver {
    private static final CachingDisabledReason CACHING_NOT_ENABLED = new CachingDisabledReason(CachingDisabledReasonCategory.NOT_CACHEABLE, "Caching has not been enabled for the task");
    private static final CachingDisabledReason CACHING_DISABLED = new CachingDisabledReason(CachingDisabledReasonCategory.NOT_CACHEABLE, "Caching has been disabled for the task");
    private static final CachingDisabledReason NO_OUTPUTS_DECLARED = new CachingDisabledReason(CachingDisabledReasonCategory.NO_OUTPUTS_DECLARED, "No outputs declared");

    private final RelativeFilePathResolver relativeFilePathResolver;

    public DefaultTaskCacheabilityResolver(RelativeFilePathResolver relativeFilePathResolver) {
        this.relativeFilePathResolver = relativeFilePathResolver;
    }

    @Override
    public Optional<CachingDisabledReason> shouldDisableCaching(
        TaskInternal task,
        TaskProperties taskProperties,
        Collection<SelfDescribingSpec<TaskInternal>> cacheIfSpecs,
        Collection<SelfDescribingSpec<TaskInternal>> doNotCacheIfSpecs,
        @Nullable OverlappingOutputs overlappingOutputs
    ) {
        if (cacheIfSpecs.isEmpty()) {
            if (task.getClass().isAnnotationPresent(DisableCachingByDefault.class)) {
                DisableCachingByDefault doNotCacheAnnotation = task.getClass().getAnnotation(DisableCachingByDefault.class);
                String reason = doNotCacheAnnotation.because();
                if (reason.isEmpty()) {
                    return Optional.of(CACHING_DISABLED);
                } else {
                    return Optional.of(new CachingDisabledReason(CachingDisabledReasonCategory.NOT_CACHEABLE, reason));
                }
            }

            return Optional.of(CACHING_NOT_ENABLED);
        }

        if (!taskProperties.hasDeclaredOutputs()) {
            return Optional.of(NO_OUTPUTS_DECLARED);
        }

        if (overlappingOutputs != null) {
            String relativePath = relativeFilePathResolver.resolveForDisplay(overlappingOutputs.getOverlappedFilePath());
            return Optional.of(new CachingDisabledReason(CachingDisabledReasonCategory.OVERLAPPING_OUTPUTS,
                "Gradle does not know how file '" + relativePath + "' was created (output property '" + overlappingOutputs.getPropertyName() + "'). Task output caching requires exclusive access to output paths to guarantee correctness (i.e. multiple tasks are not allowed to produce output in the same location)."));
        }

        Optional<String> reasonNotToTrackState = task.getReasonNotToTrackState();
        if (reasonNotToTrackState.isPresent()) {
            return Optional.of(new CachingDisabledReason(CachingDisabledReasonCategory.DISABLE_CONDITION_SATISFIED, "Task is untracked because: " + reasonNotToTrackState.get()));
        }

        for (OutputFilePropertySpec spec : taskProperties.getOutputFileProperties()) {
            if (!(spec instanceof CacheableOutputFilePropertySpec)) {
                return Optional.of(new CachingDisabledReason(
                    CachingDisabledReasonCategory.NON_CACHEABLE_OUTPUT,
                    "Output property '" + spec.getPropertyName() + "' contains a file tree"
                ));
            }
        }

        for (SelfDescribingSpec<TaskInternal> cacheIfSpec : cacheIfSpecs) {
            if (!cacheIfSpec.isSatisfiedBy(task)) {
                return Optional.of(new CachingDisabledReason(
                    CachingDisabledReasonCategory.ENABLE_CONDITION_NOT_SATISFIED,
                    "'" + cacheIfSpec.getDisplayName() + "' not satisfied"
                ));
            }
        }

        for (SelfDescribingSpec<TaskInternal> doNotCacheIfSpec : doNotCacheIfSpecs) {
            if (doNotCacheIfSpec.isSatisfiedBy(task)) {
                return Optional.of(new CachingDisabledReason(
                    CachingDisabledReasonCategory.DISABLE_CONDITION_SATISFIED,
                    "'" + doNotCacheIfSpec.getDisplayName() + "' satisfied"
                ));
            }
        }

        return Optional.empty();
    }
}
