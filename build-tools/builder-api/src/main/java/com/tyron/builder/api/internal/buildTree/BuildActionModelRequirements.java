package com.tyron.builder.api.internal.buildTree;

import com.google.common.hash.Hasher;
import com.tyron.builder.api.internal.DisplayName;
import com.tyron.builder.api.internal.StartParameterInternal;

public interface BuildActionModelRequirements {
    /**
     * Will the action run tasks?
     */
    boolean isRunsTasks();

    /**
     * Will the action create a tooling model? Note that actions can both run tasks and create a tooling model.
     */
    boolean isCreatesModel();

    StartParameterInternal getStartParameter();

    DisplayName getActionDisplayName();

    /**
     * A description of the important components of the cache key for this action.
     */
    DisplayName getConfigurationCacheKeyDisplayName();

    /**
     * Appends any additional values that should contribute to the configuration cache entry key for this action.
     * Should not append any details of the requested tasks, as these are always added when {@link #isRunsTasks()} returns true.
     */
    void appendKeyTo(Hasher hasher);
}