package com.tyron.builder.project.impl;

import com.tyron.builder.project.api.FileManager;

import java.io.File;
import java.util.Optional;

public class FileManagerImpl implements FileManager {
    @Override
    public void openFileForSnapshot(File file, String content) {

    }

    @Override
    public void setSnapshotContent(File file, String content) {

    }

    @Override
    public void closeFileForSnapshot(File file) {

    }

    @Override
    public Optional<CharSequence> getFileContent(File file) {
        return Optional.empty();
    }

    @Override
    public void shutdown() {

    }
}