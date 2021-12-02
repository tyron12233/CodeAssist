package com.tyron.builder.project.impl;

import com.tyron.builder.project.api.FileManager;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class FileManagerImpl implements FileManager {

    private final File mRoot;
    private final Map<File, String> mSnapshots;

    public FileManagerImpl(File root) {
        mRoot = root;
        mSnapshots = new HashMap<>();
    }

    @Override
    public void openFileForSnapshot(File file, String content) {
        mSnapshots.put(file, content);
    }

    @Override
    public void setSnapshotContent(File file, String content) {
        mSnapshots.computeIfPresent(file, (f, c) -> content);
    }

    @Override
    public void closeFileForSnapshot(File file) {
        mSnapshots.remove(file);
    }

    @Override
    public Optional<CharSequence> getFileContent(File file) {
        CharSequence content = mSnapshots.get(file);
        if (content != null) {
            return Optional.of(content);
        }

        try {
            return Optional.of(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
        } catch (IOException ignore) {
            // fall through
        }
        return Optional.empty();
    }

    @Override
    public void shutdown() {
        mSnapshots.clear();
    }
}