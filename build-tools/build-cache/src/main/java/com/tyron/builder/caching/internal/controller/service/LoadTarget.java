package com.tyron.builder.caching.internal.controller.service;

import com.google.common.io.Closer;
import com.google.common.io.Files;
import com.tyron.builder.caching.BuildCacheEntryReader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class LoadTarget implements BuildCacheEntryReader {

    private final File file;
    private boolean loaded;

    public LoadTarget(File file) {
        this.file = file;
    }

    @Override
    public void readFrom(InputStream input) throws IOException {
        Closer closer = Closer.create();
        closer.register(input);
        try {
            if (loaded) {
                throw new IllegalStateException("Build cache entry has already been read");
            }
            Files.asByteSink(file).writeFrom(input);
            loaded = true;
        } catch (Exception e) {
            throw closer.rethrow(e);
        } finally {
            closer.close();
        }
    }

    public boolean isLoaded() {
        return loaded;
    }

    public long getLoadedSize() {
        if (loaded) {
            return file.length();
        } else {
            return -1;
        }
    }

}
