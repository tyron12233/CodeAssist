package com.tyron.builder.dexing;

import org.gradle.util.internal.GFileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

final class FileBasedClassFileEntry implements ClassFileEntry {

    @NotNull
    private final String relativePath;
    @NotNull private final Path fullPath;
    @NotNull private final DirectoryBasedClassFileInput input;

    public FileBasedClassFileEntry(
            @NotNull Path rootPath,
            @NotNull Path fullPath,
            @NotNull DirectoryBasedClassFileInput input) {
        this.relativePath = GFileUtils.toSystemIndependentPath(rootPath.relativize(fullPath).toString());
        this.fullPath = fullPath;
        this.input = input;
    }

    @Override
    public String name() {
        return fullPath.getFileName().toString();
    }

    @Override
    public long getSize() throws IOException {
        return Files.size(fullPath);
    }

    @NotNull
    @Override
    public String getRelativePath() {
        return relativePath;
    }

    @NotNull
    @Override
    public ClassFileInput getInput() {
        return input;
    }

    @Override
    public byte[] readAllBytes() throws IOException {
        return Files.readAllBytes(fullPath);
    }

    @Override
    public int readAllBytes(byte[] bytes) throws IOException {
        try (SeekableByteChannel sbc = Files.newByteChannel(fullPath);
                InputStream in = Channels.newInputStream(sbc)) {
            long size = sbc.size();
            if (size > bytes.length) {
                throw new OutOfMemoryError("Required array size too large");
            }

            return in.read(bytes, 0, (int) size);
        }
    }
}