package com.tyron.builder.internal.execution.steps;


import com.tyron.builder.api.internal.execution.caching.CachingState;

public interface CachingContext extends ValidationFinishedContext {
    /**
     * The resolved state of caching for the work.
     */
    CachingState getCachingState();
}