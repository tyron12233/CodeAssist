package com.tyron.builder.internal.execution.steps;

import com.tyron.builder.internal.execution.ExecutionEngine;
import com.tyron.builder.internal.execution.caching.CachingState;

public interface CachingResult extends UpToDateResult, ExecutionEngine.Result {

    CachingState getCachingState();
}