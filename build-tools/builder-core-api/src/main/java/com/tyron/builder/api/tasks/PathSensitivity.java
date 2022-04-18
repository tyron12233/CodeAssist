package com.tyron.builder.api.tasks;

/**
 * Enumeration of different path handling strategies for task properties.
 *
 * @see PathSensitive
 *
 * @since 3.1
 */
public enum PathSensitivity {
    /**
     * Consider the full path of files and directories.
     *
     * <p><b>This will prevent the task's outputs from being shared across different workspaces via the build cache.</b></p>
     */
    ABSOLUTE,

    /**
     * Use the location of the file related to a hierarchy.
     *
     * <p>
     *     For files in the root of the file collection, the file name is used as the normalized path.
     *     For directories in the root of the file collection, an empty string is used as normalized path.
     *     For files in directories in the root of the file collection, the normalized path is the relative path of the file to the root directory containing it.
     * </p>
     *
     * <br>
     * Example: The property is an input directory.
     * <ul>
     *     <li>The path of the input directory is ignored.</li>
     *     <li>The path of the files in the input directory are considered relative to the input directory.</li>
     * </ul>
     */
    RELATIVE,

    /**
     * Consider only the name of files and directories.
     */
    NAME_ONLY,

    /**
     * Ignore file paths and directories altogether.
     */
    NONE
}