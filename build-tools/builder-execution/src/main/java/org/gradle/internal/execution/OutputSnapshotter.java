package org.gradle.internal.execution;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.snapshot.FileSystemSnapshot;

import java.io.File;

public interface OutputSnapshotter {
    /**
     * Takes a snapshot of the outputs of a work.
     */
    ImmutableSortedMap<String, FileSystemSnapshot> snapshotOutputs(UnitOfWork work, File workspace)
            throws OutputFileSnapshottingException;

    class OutputFileSnapshottingException extends RuntimeException {
        private final String propertyName;

        public OutputFileSnapshottingException(String propertyName, Throwable cause) {
            this(String.format("Cannot snapshot output property '%s'.", propertyName), cause, propertyName);
        }

        private OutputFileSnapshottingException(String formattedMessage, Throwable cause, String propertyName) {
            super(formattedMessage, cause);
            this.propertyName = propertyName;
        }

        public String getPropertyName() {
            return propertyName;
        }
    }
}