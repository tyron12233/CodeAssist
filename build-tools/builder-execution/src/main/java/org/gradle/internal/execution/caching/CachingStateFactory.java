package org.gradle.internal.execution.caching;

import com.google.common.collect.ImmutableList;
import org.gradle.internal.execution.history.BeforeExecutionState;

public interface CachingStateFactory {
    CachingState createCachingState(BeforeExecutionState beforeExecutionState, ImmutableList<CachingDisabledReason> cachingDisabledReasons);
}