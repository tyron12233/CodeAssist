package com.tyron.builder.project.impl;

import android.util.Log;

import com.tyron.builder.project.api.FileManager;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileManagerImpl implements FileManager {

    private static final String TAG = FileManagerImpl.class.getSimpleName();

    private final ExecutorService mService;
    private final File mRoot;
    private final Map<File, String> mSnapshots;

    public FileManagerImpl(File root) {
        mRoot = root;
        mService = Executors.newSingleThreadExecutor();
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
        if (!file.exists()) {
            return;
        }
        if (mSnapshots.containsKey(file)) {
            try {
                FileUtils.writeStringToFile(file, mSnapshots.get(file), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.d(TAG, "Failed to save file " + file.getName(), e);
            }
            mSnapshots.remove(file);
        }
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
        mSnapshots.forEach((k, v) -> mService.execute(() -> {
            try {
                FileUtils.writeStringToFile(k, v, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // ignored
            }
        }));
    }
}