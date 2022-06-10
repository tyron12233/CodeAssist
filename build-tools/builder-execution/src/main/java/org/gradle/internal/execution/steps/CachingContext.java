package org.gradle.internal.execution.steps;


import org.gradle.internal.execution.caching.CachingState;

public interface CachingContext extends ValidationFinishedContext {
    /**
     * The resolved state of caching for the work.
     */
    CachingState getCachingState();
}