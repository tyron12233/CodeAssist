package com.tyron.builder.api.work;

import com.tyron.builder.api.internal.file.FileType;

import java.io.File;

/**
 * A change to a file.
 *
 * @since 5.4
 */
public interface FileChange {

    /**
     * The file, which may no longer exist.
     */
    File getFile();

    /**
     * The type of change to the file.
     */
    ChangeType getChangeType();

    /**
     * The file type of the file.
     *
     * <p>
     *     For {@link ChangeType#ADDED} and {@link ChangeType#MODIFIED}, the type of the file which was added/modified is reported.
     *     For {@link ChangeType#REMOVED} the type of the file which was removed is reported.
     * </p>
     */
    FileType getFileType();

    /**
     * The normalized path of the file, as specified by the path normalization strategy.
     *
     * <p>
     *    See {@link org.gradle.api.tasks.PathSensitivity}, {@link org.gradle.api.tasks.Classpath} and {@link org.gradle.api.tasks.CompileClasspath} for the different path normalization strategies.
     * </p>
     */
    String getNormalizedPath();
}