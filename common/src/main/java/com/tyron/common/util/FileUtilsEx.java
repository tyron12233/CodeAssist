package com.tyron.common.util;

import java.io.File;
import java.io.IOException;

public class FileUtilsEx {

    /**
     * Creates the given file and throws {@link IOException} if it fails,
     * also checks if the file already exists before creating
     */
    public static void createFile(File file) throws IOException {
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("Unable to create file: " + file);
        }
    }
}
