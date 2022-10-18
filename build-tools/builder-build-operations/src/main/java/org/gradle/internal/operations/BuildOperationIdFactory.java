package org.gradle.internal.operations;

/**
 * Provides an id unique within the global scope a build VM.
 */
public interface BuildOperationIdFactory {

    /**
     * @return the next unique ID
     */
    long nextId();
}