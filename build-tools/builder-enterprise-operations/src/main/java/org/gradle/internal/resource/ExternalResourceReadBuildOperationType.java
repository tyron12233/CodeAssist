package org.gradle.internal.resource;

import org.gradle.internal.operations.BuildOperationType;

/**
 * A read of the content of an external resource.
 *
 * @since 4.0
 */
public final class ExternalResourceReadBuildOperationType implements BuildOperationType<ExternalResourceReadBuildOperationType.Details, ExternalResourceReadBuildOperationType.Result> {

    public interface Details {

        /**
         * The location of the resource.
         * A valid URI.
         */
        String getLocation();

    }

    public interface Result {

        /**
         * The number of bytes of the resource that were read.
         */
        long getBytesRead();

    }

    private ExternalResourceReadBuildOperationType() {
    }

}