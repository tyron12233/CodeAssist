package org.gradle.internal.execution.history;

import org.gradle.internal.snapshot.FileSystemSnapshot;

import java.io.File;

/**
 * A registry for files generated by the Gradle build.
 */
public interface OutputFilesRepository {
    boolean isGeneratedByGradle(File file);

    void recordOutputs(Iterable<? extends FileSystemSnapshot> outputSnapshots);
}