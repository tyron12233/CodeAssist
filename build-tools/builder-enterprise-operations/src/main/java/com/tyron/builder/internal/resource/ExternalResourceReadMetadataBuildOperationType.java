package com.tyron.builder.internal.resource;

import com.tyron.builder.internal.operations.BuildOperationType;

/**
 * A read of the metadata of an external resource.
 *
 * @since 4.0
 */
public final class ExternalResourceReadMetadataBuildOperationType implements BuildOperationType<ExternalResourceReadMetadataBuildOperationType.Details, ExternalResourceReadMetadataBuildOperationType.Result> {

    public interface Details {

        /**
         * The location of the resource.
         * A valid URI.
         */
        String getLocation();

    }

    public interface Result {

    }

    private ExternalResourceReadMetadataBuildOperationType() {
    }

}
