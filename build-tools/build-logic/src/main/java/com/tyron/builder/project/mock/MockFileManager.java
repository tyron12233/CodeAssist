package com.tyron.builder.project.mock;

import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.listener.FileListener;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A simple implementation of {@link FileManager} that only loads file content
 * as-is.
 */
public class MockFileManager implements FileManager {

    private final File mRoot;
    private final Map<File, CharSequence> mSnapshots;

    public MockFileManager(File root) {
        mRoot = root;
        mSnapshots = new HashMap<>();
    }

    @Override
    public boolean isOpened(File file) {
        return mSnapshots.containsKey(file);
    }

    @Override
    public void openFileForSnapshot(File file, String content) {
        mSnapshots.put(file, content);
    }

    @Override
    public void setSnapshotContent(File file, String content, boolean notify) {

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
    public void addSnapshotListener(FileListener listener) {

    }

    @Override
    public void removeSnapshotListener(FileListener listener) {

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
        // no-op
    }
}
