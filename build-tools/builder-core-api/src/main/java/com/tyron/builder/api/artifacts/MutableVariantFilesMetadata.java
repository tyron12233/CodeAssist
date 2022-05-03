package com.tyron.builder.api.artifacts;

import com.tyron.builder.api.Action;

/**
 * Mutable information about the files that belong to a variant.
 *
 * @since 6.0
 */
public interface MutableVariantFilesMetadata {

    /**
     * Remove all files already defined for the variant.
     * Useful when files where initialized from a base variant or configuration using
     * {@link ComponentMetadataDetails#addVariant(String, String, Action)} .
     */
    void removeAllFiles();

    /**
     * Add a file, if the file location is the same as the file name.
     *
     * @param name name and path of the file.
     */
    void addFile(String name);

    /**
     * Add a file.
     *
     * @param name name of the file
     * @param url location of the file, if not located next to the metadata in the repository
     */
    void addFile(String name, String url);
}
