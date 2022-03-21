package com.tyron.builder.api.internal.file;

/**
 * The metadata of a file.
 */
public interface FileMetadata {
    FileType getType();

    /**
     * Note: always 0 for directories and missing files.
     */
    long getLastModified();

    /**
     * Note: always 0 for directories and missing files.
     */
    long getLength();

    /**
     * How the file with the metadata was accessed.
     */
    AccessType getAccessType();

    /**
     * How the file with the metadata was accessed.
     *
     * {@link AccessType} only gives information about the queried path, not about parents of the queried path.
     *
     * If we have a symlink situation like `symlink1` -&gt; `symlink2` -&gt; `target`,
     * then the metadata for `symlink1` will have an access type {@link AccessType#VIA_SYMLINK}
     * and the metadata of `target`, i.e. the accessor will resolve transitive symlinks.
     *
     * If the directory `symlinkedDir` -&gt; `targetDir` is a symlink, then the
     * metadata of `symlinkedDir/fileInDir` will be {@link AccessType#DIRECT},
     * given the file `targetDir/fileInDir` exists.
     */
    enum AccessType {
        /**
         * The path pointed to the file directly.
         */
        DIRECT,

        /**
         * The path pointed to a symlink pointing to the file with the metadata.
         */
        VIA_SYMLINK;

        /**
         * Factory method for returning the access type based on a boolean.
         */
        public static AccessType viaSymlink(boolean viaSymlink) {
            return viaSymlink
                    ? VIA_SYMLINK
                    : DIRECT;
        }
    }
}