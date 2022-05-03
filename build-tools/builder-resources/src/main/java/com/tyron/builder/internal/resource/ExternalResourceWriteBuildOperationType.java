package com.tyron.builder.internal.resource;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * A write of content to an external resource.
 *
 * @since 4.0
 */
public final class ExternalResourceWriteBuildOperationType implements BuildOperationType<ExternalResourceWriteBuildOperationType.Details, ExternalResourceWriteBuildOperationType.Result> {

//    @UsedByScanPlugin
    public interface Details {

        /**
         * The location of the resource.
         * A valid URI.
         */
        String getLocation();

    }

//    @UsedByScanPlugin
    public interface Result {

        /**
         * The number of bytes that were written to the resource
         */
        long getBytesWritten();

    }

    private ExternalResourceWriteBuildOperationType() {
    }

}
