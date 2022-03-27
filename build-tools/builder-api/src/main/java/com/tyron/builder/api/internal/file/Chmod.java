package com.tyron.builder.api.internal.file;

import java.io.File;

public interface Chmod {
    /**
     * Changes the Unix permissions of a provided file. Implementations that don't
     * support Unix permissions may choose to ignore this request.
     *
     * @param file the file to change permissions on
     * @param mode the permissions, e.g. 0755
     * @throws FileException if the permissions can't be changed for some reason.
     */
    void chmod(File file, int mode) throws FileException;
}