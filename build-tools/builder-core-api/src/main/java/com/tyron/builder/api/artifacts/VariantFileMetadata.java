package com.tyron.builder.api.artifacts;

/**
 * Part of a component variant's metadata representing a file and its location.
 *
 * @since 6.0
 */
public interface VariantFileMetadata {

    /**
     * Get the name of the file.
     *
     * @return the name of the file
     */
    String getName();

    /**
     * Get the location of the file relative to the corresponding metadata file in the repository.
     * This is the same as the file name, if the file is located next to the metadata file.
     *
     * @return relative location of the file
     */
    String getUrl();

}
