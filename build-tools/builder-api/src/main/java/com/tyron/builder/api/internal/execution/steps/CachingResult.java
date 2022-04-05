package com.tyron.builder.api.internal.execution.steps;

import com.tyron.builder.api.internal.execution.ExecutionEngine;
import com.tyron.builder.api.internal.execution.caching.CachingState;

public interface CachingResult extends UpToDateResult, ExecutionEngine.Result {

    CachingState getCachingState();
}