package com.tyron.builder.api.internal.tasks;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

public interface TaskExecuterResult {
    /**
     * Returns the reasons for executing this task. An empty list means the task was not executed.
     */
    List<String> getExecutionReasons();

    /**
     * Whether the task was executed incrementally.
     */
    boolean executedIncrementally();

    /**
     * If the execution resulted in some previous output being reused, this returns its origin metadata.
     */
    // Optional<OriginMetadata>
    Optional<Object> getReusedOutputOriginMetadata();

    /**
     * The caching state of the task, including all its captured inputs and the cache key if calculated.
     */
    // CachingState
    Object getCachingState();

    TaskExecuterResult WITHOUT_OUTPUTS = new TaskExecuterResult() {
        @Override
        public List<String> getExecutionReasons() {
            return ImmutableList.of();
        }

        @Override
        public boolean executedIncrementally() {
            return false;
        }

        @Override
        public Optional<Object> getReusedOutputOriginMetadata() {
            return Optional.empty();
        }

        @Override
        public Object getCachingState() {
            return null;
        }
    };
}