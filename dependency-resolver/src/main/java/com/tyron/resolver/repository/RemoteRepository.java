package com.tyron.resolver.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

public class RemoteRepository extends DefaultRepository {

    private final String mName;
    private final String mUrl;

    private final LocalRepository mLocalRepository;

    /**
     * @param name The name of the directory on which this repository will store caches into
     * @param url The url to search for files
     */
    public RemoteRepository(String name, String url) {
        if (!url.endsWith("/")) {
            url = url + "/";
        }
        mName = name;
        mUrl = url;
        mLocalRepository = new LocalRepository(name);
    }

    @Override
    public String getName() {
        return mName;
    }

    @Override
    public void setCacheDirectory(@NonNull File file) {
        super.setCacheDirectory(file);

        mLocalRepository.setCacheDirectory(file);
    }

    @Nullable
    @Override
    public InputStream getInputStream(String path) throws IOException {
        File file = getFile(path);
        if (file == null || !file.exists()) {
            return null;
        }
        return FileUtils.openInputStream(file);
    }

    @Nullable
    @Override
    public File getFile(String path) throws IOException {
        File file = mLocalRepository.getFile(path);
        if (file != null && file.exists()) {
            return file;
        }
        return getFileInternal(path);
    }

    @Nullable
    @Override
    public File getCachedFile(String path) throws IOException {
        return mLocalRepository.getCachedFile(path);
    }

    private File getFileInternal(String path) throws IOException {
        String downloadUrl = mUrl + path;
        URL url = new URL(downloadUrl);
        try {
            InputStream inputStream = url.openStream();
            if (inputStream != null) {
                // save the file to cache, and then return the one from there
                return mLocalRepository.save(path, inputStream);
            }
        } catch (IOException e) {
            // ignored, return null
        }
        return null;
    }
}
