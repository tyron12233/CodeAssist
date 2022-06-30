package com.tyron.builder.api.file;

import java.io.File;

/**
 * Represents a regular file at a fixed location on the file system. A regular file is a file that is not a directory and is not some special kind of file such as a device.
 * <p>
 * <b>Note:</b> This interface is not intended for implementation by build script or plugin authors. An instance of this class can be created
 * from a {@link Directory} instance using the {@link Directory#file(String)} method or via various methods on {@link ProjectLayout} such as {@link ProjectLayout#getProjectDirectory()}.
 *
 * @since 4.1
 */
public interface RegularFile extends FileSystemLocation {
    /**
     * Returns the location of this file, as an absolute {@link File}.
     */
    @Override
    File getAsFile();
}