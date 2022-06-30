package com.tyron.code.ui.file.event;

import androidx.annotation.NonNull;

import com.tyron.code.event.Event;

import java.io.File;

/**
 * Used to notify the file manager that its root needs to be refreshed
 */
public class RefreshRootEvent extends Event {

    private final File mRoot;

    public RefreshRootEvent(@NonNull File root) {
        mRoot = root;
    }

    @NonNull
    public File getRoot() {
        return mRoot;
    }
}
