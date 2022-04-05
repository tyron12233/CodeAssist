package com.tyron.builder.api.internal.execution.caching;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.internal.execution.history.BeforeExecutionState;

public interface CachingStateFactory {
    CachingState createCachingState(BeforeExecutionState beforeExecutionState, ImmutableList<CachingDisabledReason> cachingDisabledReasons);
}