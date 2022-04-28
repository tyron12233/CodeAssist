package com.tyron.builder.internal.execution.steps;


import com.tyron.builder.internal.execution.caching.CachingState;

public interface CachingContext extends ValidationFinishedContext {
    /**
     * The resolved state of caching for the work.
     */
    CachingState getCachingState();
}