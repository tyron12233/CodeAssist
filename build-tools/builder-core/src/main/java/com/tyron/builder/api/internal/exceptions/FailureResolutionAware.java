package com.tyron.builder.api.internal.exceptions;

import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.initialization.BuildClientMetaData;

import java.util.function.Consumer;

/**
 * Enhancement interface that exceptions can implement to provide additional information on how to resolve the failure.
 */
public interface FailureResolutionAware {

    void appendResolutions(Context context);

    interface Context {
        BuildClientMetaData getClientMetaData();

        /**
         * Indicates that the build definition is missing.
         */
        void doNotSuggestResolutionsThatRequireBuildDefinition();

        void appendResolution(Consumer<StyledTextOutput> resolution);
    }
}