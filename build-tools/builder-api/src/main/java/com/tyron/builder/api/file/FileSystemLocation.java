package com.tyron.builder.api.file;

import java.io.File;

/**
 * Represents some immutable location on the file system.
 *
 * <p><b>Note:</b> This interface is not intended for implementation by build script or plugin authors.
 *
 * @since 4.2
 */
public interface FileSystemLocation {
    /**
     * Returns this location as an absolute {@link File}.
     *
     * @return the File
     */
    File getAsFile();
}