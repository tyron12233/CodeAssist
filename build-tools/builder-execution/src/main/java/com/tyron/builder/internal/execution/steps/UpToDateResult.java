package com.tyron.builder.internal.execution.steps;


import com.google.common.collect.ImmutableList;
import com.tyron.builder.caching.internal.origin.OriginMetadata;

import java.util.Optional;

public interface UpToDateResult extends AfterExecutionResult {
    /**
     * A list of messages describing the first few reasons encountered that caused the work to be executed.
     * An empty list means the work was up-to-date and hasn't been executed.
     */
    ImmutableList<String> getExecutionReasons();

    /**
     * If a previously produced output was reused in some way, the reused output's origin metadata is returned.
     */
    Optional<OriginMetadata> getReusedOutputOriginMetadata();
}