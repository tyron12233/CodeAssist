package com.tyron.resolver.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public interface Repository {

    /**
     * Retrieve an {@link InputStream} from this repository
     * @param path The path of the file relative to the url
     * @return the input stream for the file
     * @throws IOException if there is an error while retrieving the file
     */
    @Nullable
    InputStream getInputStream(String path) throws IOException;

    @Nullable File getFile(String path) throws IOException;

    @Nullable
    File getCachedFile(String path) throws IOException;

    String getName();

    /**
     * Sets the directory where this repository can save files into.
     * @param file the directory, never null
     */
    void setCacheDirectory(@NonNull File file);

    @NonNull
    File getCacheDirectory();

    @Nullable
    default File getRootDirectory() {
        String name = getName();
        if (name == null) {
            return null;
        }
        return new File(getCacheDirectory(), getName());
    }
}
