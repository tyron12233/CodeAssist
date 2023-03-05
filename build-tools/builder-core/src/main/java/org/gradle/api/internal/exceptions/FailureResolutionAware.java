package org.gradle.api.internal.exceptions;

import org.gradle.internal.logging.text.StyledTextOutput;
import org.gradle.initialization.BuildClientMetaData;

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