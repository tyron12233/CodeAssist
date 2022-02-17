package com.tyron.builder.project.impl;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.builder.project.api.FileManager;
import com.tyron.builder.project.listener.FileListener;
import com.tyron.common.util.ThreadUtil;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileManagerImpl implements FileManager {

    private static class FileState {

        private String mContents;
        private Instant mModified;

        public FileState(String contents, Instant modified) {
            mContents = contents;
            mModified = modified;
        }

        public String getContents() {
            return mContents;
        }

        public Instant getModified() {
            return mModified;
        }

        public void setContents(String content) {
            mContents = content;
        }

        public void setModified(Instant now) {
            mModified = now;
        }
    }

    private static final String TAG = FileManagerImpl.class.getSimpleName();

    private final ExecutorService mService;
    private final File mRoot;
    private final Map<File, FileState> mSnapshots;

    private final List<FileListener> mListeners = new ArrayList<>();

    public FileManagerImpl(File root) {
        mRoot = root;
        mService = Executors.newSingleThreadExecutor();
        mSnapshots = Collections.synchronizedMap(new HashMap<>());
    }

    @Override
    public boolean isOpened(@NonNull File file) {
        return mSnapshots.get(file) != null;
    }

    @Nullable
    @Override
    public Instant getLastModified(@NonNull File file) {
        FileState state = mSnapshots.get(file);
        if (state == null) {
            return null;
        }
        return state.getModified();
    }

    @Override
    public void setLastModified(@NonNull File file, Instant instant) {
        FileState state = mSnapshots.get(file);
        if (state == null) {
            return;
        }
        state.setModified(instant);

        for (FileListener listener : mListeners) {
            listener.onSnapshotChanged(file, state.getContents());
        }
    }

    @Override
    public void openFileForSnapshot(@NonNull File file, String content) {
        long lastModified = file.lastModified();
        FileState state = new FileState(content, Instant.ofEpochMilli(lastModified));
        mSnapshots.put(file, state);
    }

    @Override
    public void setSnapshotContent(@NonNull File file, String content, FileListener listener) {
        if (!mSnapshots.containsKey(file)) {
            return;
        }

        mSnapshots.computeIfPresent(file, (f, state) -> {
            boolean equals = Objects.equals(content, state.getContents());
            state.setContents(content);
            if (!equals) {
                state.setModified(Instant.now());
            }
            return state;
        });

        for (FileListener l : mListeners) {
            if (l.equals(listener)) {
                continue;
            }
            l.onSnapshotChanged(file, content);
        }
    }

    @Override
    public void setSnapshotContent(@NonNull File file, String content, boolean notify) {
        if (!mSnapshots.containsKey(file)) {
            return;
        }

        mSnapshots.computeIfPresent(file, (f, state) -> {
            boolean equals = Objects.equals(content, state.getContents());
            state.setContents(content);
            if (!equals) {
                state.setModified(Instant.now());
            }
            return state;
        });
        if (notify) {
            for (FileListener listener : mListeners) {
                listener.onSnapshotChanged(file, content);
            }
        }
    }

    @Override
    public void closeFileForSnapshot(@NonNull File file) {
        if (!file.exists()) {
            return;
        }
        if (mSnapshots.containsKey(file)) {
            try {
                FileState state = mSnapshots.get(file);
                FileUtils.writeStringToFile(file,
                        state.getContents(),
                        StandardCharsets.UTF_8);
            } catch (IOException e) {
                Log.d(TAG, "Failed to save file " + file.getName(), e);
            }
            mSnapshots.remove(file);
        }
    }

    @Override
    public synchronized void addSnapshotListener(FileListener listener) {
        mListeners.add(listener);
    }

    @Override
    public synchronized void removeSnapshotListener(FileListener listener) {
        mListeners.remove(listener);
    }

    @Override
    public Optional<CharSequence> getFileContent(File file) {
        FileState state = mSnapshots.get(file);
        if (state != null) {
            return Optional.of(state.getContents());
        }
        return Optional.empty();
    }

    @Override
    public void shutdown() {
        saveContents();
    }

    @Override
    public void saveContents() {
        mService.execute(() -> mSnapshots.forEach((k, v) -> {
            try {
                FileUtils.writeStringToFile(k,
                                            v.getContents(), StandardCharsets.UTF_8);
                Instant instant = Instant.ofEpochMilli(k.lastModified());
                ThreadUtil.runOnUiThread(() -> setLastModified(k, instant));
            } catch (IOException e) {
                // ignored
            }
        }));
    }
}