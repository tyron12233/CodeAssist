package com.tyron.resolver.repository;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class DefaultRepository implements Repository {

    private File mCacheDirectory;

    @Nullable
    @Override
    public InputStream getInputStream(String path) throws IOException {
        return null;
    }

    @Nullable
    @Override
    public File getFile(String path) throws IOException {
        return null;
    }

    @Nullable
    @Override
    public File getCachedFile(String path) throws IOException {
        return null;
    }

    @Override
    public String getName() {
        return null;
    }

    @Override
    public void setCacheDirectory(@NonNull File file) {
        mCacheDirectory = file;
    }

    @NonNull
    @Override
    public File getCacheDirectory() {
        return mCacheDirectory;
    }
}
