package com.tyron.builder.internal.file.archive.impl;

import com.tyron.builder.internal.file.archive.ZipEntry;

import java.io.InputStream;
import java.util.function.Supplier;

import com.google.common.io.ByteStreams;
import javax.annotation.Nullable;
import java.io.IOException;

class JdkZipEntry implements ZipEntry {

    private final java.util.zip.ZipEntry entry;
    private final Supplier<InputStream> inputStreamSupplier;
    private final Runnable closeAction;

    public JdkZipEntry(java.util.zip.ZipEntry entry, Supplier<InputStream> inputStreamSupplier, @Nullable Runnable closeAction) {
        this.entry = entry;
        this.inputStreamSupplier = inputStreamSupplier;
        this.closeAction = closeAction;
    }

    @Override
    public boolean isDirectory() {
        return entry.isDirectory();
    }

    @Override
    public String getName() {
        return entry.getName();
    }

    @Override
    public byte[] getContent() throws IOException {
        return withInputStream(new InputStreamAction<byte[]>() {
            @Override
            public byte[] run(InputStream inputStream) throws IOException {
                int size = size();
                if (size >= 0) {
                    byte[] content = new byte[size];
                    ByteStreams.readFully(inputStream, content);
                    return content;
                } else {
                    return ByteStreams.toByteArray(inputStream);
                }
            }
        });
    }

    @Override
    public <T> T withInputStream(InputStreamAction<T> action) throws IOException {
        InputStream is = inputStreamSupplier.get();
        try {
            return action.run(is);
        } finally {
            if (closeAction != null) {
                closeAction.run();
            } else {
                is.close();
            }
        }
    }

    @Override
    public int size() {
        return (int) entry.getSize();
    }
}