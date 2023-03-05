package org.gradle.internal.resource;

import org.gradle.internal.operations.BuildOperationType;

/**
 * A listing of an external resource.
 *
 * A listing operation is analogous to a directory listing.
 *
 * @since 4.0
 */
public final class ExternalResourceListBuildOperationType implements BuildOperationType<ExternalResourceListBuildOperationType.Details, ExternalResourceListBuildOperationType.Result> {

    public interface Details {

        /**
         * The location of the resource.
         * A valid URI.
         */
        String getLocation();

    }

    public interface Result {

    }



    private ExternalResourceListBuildOperationType() {
    }

}
