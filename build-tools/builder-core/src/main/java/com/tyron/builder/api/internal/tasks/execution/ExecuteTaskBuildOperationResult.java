package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.internal.execution.caching.CachingDisabledReason;
import com.tyron.builder.internal.execution.caching.CachingDisabledReasonCategory;
import com.tyron.builder.internal.execution.caching.CachingState;
import com.tyron.builder.api.internal.tasks.TaskOutputCachingDisabledReasonCategory;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

import java.util.List;

import javax.annotation.Nullable;

import java.util.Optional;

public class ExecuteTaskBuildOperationResult implements ExecuteTaskBuildOperationType.Result {

    private final TaskStateInternal taskState;
    private final CachingState cachingState;
    private final OriginMetadata originMetadata;
    private final boolean incremental;
    private final List<String> executionReasons;

    public ExecuteTaskBuildOperationResult(TaskStateInternal taskState, CachingState cachingState, @Nullable OriginMetadata originMetadata, boolean incremental, List<String> executionReasons) {
        this.taskState = taskState;
        this.cachingState = cachingState;
        this.originMetadata = originMetadata;
        this.incremental = incremental;
        this.executionReasons = executionReasons;
    }

    @Nullable
    @Override
    public String getSkipMessage() {
        return taskState.getSkipMessage();
    }

    @Override
    public boolean isActionable() {
        return taskState.isActionable();
    }

    @Nullable
    @Override
    public String getOriginBuildInvocationId() {
        return originMetadata == null ? null : originMetadata.getBuildInvocationId();
    }

    @Nullable
    @Override
    public Long getOriginExecutionTime() {
        return originMetadata == null ? null : originMetadata.getExecutionTime().toMillis();
    }

    @Nullable
    @Override
    public String getCachingDisabledReasonMessage() {
        return getCachingDisabledReason()
                .map(CachingDisabledReason::getMessage)
                .orElse(null);
    }

    @Nullable
    @Override
    public String getCachingDisabledReasonCategory() {
        return getCachingDisabledReason()
                .map(CachingDisabledReason::getCategory)
                .map(ExecuteTaskBuildOperationResult::convertNoCacheReasonCategory)
                .map(Enum::name)
                .orElse(null);
    }

    private Optional<CachingDisabledReason> getCachingDisabledReason() {
        return cachingState
                .whenDisabled()
                .map(CachingState.Disabled::getDisabledReasons)
                .map(reasons -> reasons.get(0));
    }

    private static TaskOutputCachingDisabledReasonCategory convertNoCacheReasonCategory(
            CachingDisabledReasonCategory category) {
        switch (category) {
            case UNKNOWN:
                return TaskOutputCachingDisabledReasonCategory.UNKNOWN;
            case BUILD_CACHE_DISABLED:
                return TaskOutputCachingDisabledReasonCategory.BUILD_CACHE_DISABLED;
            case NOT_CACHEABLE:
                return TaskOutputCachingDisabledReasonCategory.NOT_ENABLED_FOR_TASK;
            case ENABLE_CONDITION_NOT_SATISFIED:
                return TaskOutputCachingDisabledReasonCategory.CACHE_IF_SPEC_NOT_SATISFIED;
            case DISABLE_CONDITION_SATISFIED:
                return TaskOutputCachingDisabledReasonCategory.DO_NOT_CACHE_IF_SPEC_SATISFIED;
            case NO_OUTPUTS_DECLARED:
                return TaskOutputCachingDisabledReasonCategory.NO_OUTPUTS_DECLARED;
            case NON_CACHEABLE_OUTPUT:
                return TaskOutputCachingDisabledReasonCategory.NON_CACHEABLE_TREE_OUTPUT;
            case OVERLAPPING_OUTPUTS:
                return TaskOutputCachingDisabledReasonCategory.OVERLAPPING_OUTPUTS;
            case VALIDATION_FAILURE:
                return TaskOutputCachingDisabledReasonCategory.VALIDATION_FAILURE;
            default:
                throw new AssertionError();
        }
    }

    @Override
    public List<String> getUpToDateMessages() {
        return executionReasons;
    }

    @Override
    public boolean isIncremental() {
        return incremental;
    }

}