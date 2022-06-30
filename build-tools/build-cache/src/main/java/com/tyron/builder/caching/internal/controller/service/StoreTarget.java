package com.tyron.builder.caching.internal.controller.service;

import com.google.common.io.Closer;
import com.google.common.io.Files;
import com.tyron.builder.caching.BuildCacheEntryWriter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;

public class StoreTarget implements BuildCacheEntryWriter {

    private final File file;
    private boolean stored;

    public StoreTarget(File file) {
        this.file = file;
    }

    @Override
    public void writeTo(OutputStream output) throws IOException {
        Closer closer = Closer.create();
        closer.register(output);
        try {
            stored = true;
            Files.asByteSource(file).copyTo(output);
        } catch (Exception e) {
            throw closer.rethrow(e);
        } finally {
            closer.close();
        }
    }

    public boolean isStored() {
        return stored;
    }

    @Override
    public long getSize() {
        return file.length();
    }
}
