package org.gradle.internal.execution.steps;

import org.gradle.internal.execution.ExecutionEngine;
import org.gradle.internal.execution.caching.CachingState;

public interface CachingResult extends UpToDateResult, ExecutionEngine.Result {

    CachingState getCachingState();
}