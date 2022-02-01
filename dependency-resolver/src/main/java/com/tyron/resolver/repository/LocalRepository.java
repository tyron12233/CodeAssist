package com.tyron.resolver.repository;

import androidx.annotation.Nullable;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * A local repository is a repository which stores files in the disk instead from a remote one
 */
public class LocalRepository extends DefaultRepository {

    private final String mName;

    public LocalRepository(String name) {
        mName = name;
    }

    @Override
    public String getName() {
        return mName;
    }

    @Nullable
    @Override
    public InputStream getInputStream(String path) throws IOException {
        File file = getFile(path);
        if (file == null) {
            return null;
        }
        // shouldn't throw an IOException, file is guaranteed to exist
        return FileUtils.openInputStream(file);
    }

    @Nullable
    @Override
    public File getFile(String path) throws IOException {
        File rootDirectory = getRootFile();
        if (!rootDirectory.exists()) {
            FileUtils.forceMkdirParent(rootDirectory);
        }

        File file = new File(rootDirectory, path);
        // the file is not found on the disk, return null
        if (!file.exists()) {
            return null;
        }

        return file;
    }

    @Nullable
    @Override
    public File getCachedFile(String path) throws IOException {
        File rootDirectory = getRootFile();
        if (!rootDirectory.exists()) {
            FileUtils.forceMkdirParent(rootDirectory);
        }

        File file = new File(rootDirectory, path);
        // the file is not found on the disk, return null
        if (!file.exists()) {
            return null;
        }
        return file;
    }

    /**
     * Saves the file to this repository
     * @param path The path of the file relative to the URL
     * @param inputStream The input stream of the file
     * @return The file that was saved from the disk
     * @throws IOException if an error occurred while saving the file.
     */
    public File save(String path, InputStream inputStream) throws IOException {
        File rootDirectory = getRootFile();
        if (!rootDirectory.exists()) {
            FileUtils.forceMkdir(rootDirectory);
        }

        File file = new File(rootDirectory, path);
        FileUtils.forceMkdirParent(file);
        if (!file.exists() && !file.createNewFile()) {
            throw new IOException("Failed to create file.");
        }

        FileUtils.copyInputStreamToFile(inputStream, file);
        return file;
    }

    private File getRootFile() {
        return new File(getCacheDirectory(), mName);
    }
}
