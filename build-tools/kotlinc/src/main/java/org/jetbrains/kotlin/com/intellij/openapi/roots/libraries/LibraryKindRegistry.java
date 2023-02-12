package org.jetbrains.kotlin.com.intellij.openapi.roots.libraries;

import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;

public class LibraryKindRegistry {

    public static LibraryKindRegistry getInstance() {
        return ApplicationManager.getApplication().getService(LibraryKindRegistry.class);
    }

    @Nullable
    public LibraryKind findKindById(String id) {
        return LibraryKind.findByIdInternal(id);
    }
}
