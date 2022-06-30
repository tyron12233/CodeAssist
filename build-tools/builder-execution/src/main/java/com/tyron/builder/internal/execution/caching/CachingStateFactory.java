package com.tyron.builder.internal.execution.caching;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.internal.execution.history.BeforeExecutionState;

public interface CachingStateFactory {
    CachingState createCachingState(BeforeExecutionState beforeExecutionState, ImmutableList<CachingDisabledReason> cachingDisabledReasons);
}