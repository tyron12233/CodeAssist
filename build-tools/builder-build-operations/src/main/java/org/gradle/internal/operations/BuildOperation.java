package org.gradle.internal.operations;

/**
 * Represents some chunk of work.
 */
public interface BuildOperation {
    /**
     * Returns a description of the build operation for visualization purposes.
     */
    BuildOperationDescriptor.Builder description();
}