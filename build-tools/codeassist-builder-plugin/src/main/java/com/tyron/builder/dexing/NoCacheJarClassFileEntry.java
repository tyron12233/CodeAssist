package com.tyron.builder.dexing;

import com.google.common.io.ByteStreams;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class NoCacheJarClassFileEntry implements ClassFileEntry {

    @NotNull
    private final ZipEntry entry;
    @NotNull private final ZipFile zipFile;
    @NotNull private final ClassFileInput input;

    public NoCacheJarClassFileEntry(
            @NotNull ZipEntry entry, @NotNull ZipFile zipFile, @NotNull ClassFileInput input) {
        this.entry = entry;
        this.zipFile = zipFile;
        this.input = input;
    }

    @Override
    public String name() {
        return "Zip:" + entry.getName();
    }

    @Override
    public long getSize() {
        return entry.getSize();
    }

    @Override
    public String getRelativePath() {
        return entry.getName();
    }

    @NotNull
    @Override
    public ClassFileInput getInput() {
        return input;
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        return ByteStreams.toByteArray(new BufferedInputStream(zipFile.getInputStream(entry)));
    }

    @Override
    public int readAllBytes(byte[] bytes) throws IOException {
        try (InputStream is = new BufferedInputStream(zipFile.getInputStream(entry))) {
            return ByteStreams.read(is, bytes, 0, bytes.length);
        }
    }
}