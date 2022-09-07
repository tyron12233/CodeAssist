package com.tyron.builder.api.transform;

/**
 * The format in which content is stored.
 * @deprecated
 */
@Deprecated
public enum Format {

    /**
     * The content is a jar.
     */
    JAR,
    /**
     * The content is a directory.
     * <p>
     * This means that in the case of java class files, the files should be in directories
     * matching their package names, directly under the root directory.
     */
    DIRECTORY
}
