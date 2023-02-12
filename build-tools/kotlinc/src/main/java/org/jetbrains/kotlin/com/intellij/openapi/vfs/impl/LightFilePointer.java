package org.jetbrains.kotlin.com.intellij.openapi.vfs.impl;

import static org.jetbrains.kotlin.com.intellij.util.PathUtil.toPresentableUrl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.kotlin.com.intellij.openapi.application.Application;
import org.jetbrains.kotlin.com.intellij.openapi.application.ApplicationManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFilePointer;

public class LightFilePointer implements VirtualFilePointer {


    private final String url;

    private volatile VirtualFile file = null;

    private volatile boolean isRefreshed = false;

    public LightFilePointer(String url) {
        this.url = url;
    }

    public LightFilePointer(VirtualFile file) {
        this.file = file;
        this.url = file.getUrl();
    }

    @Nullable
    @Override
    public VirtualFile getFile() {
        refreshFile();
        return file;
    }

    @NonNull
    @Override
    public String getUrl() {
        return url;
    }

    @NonNull
    @Override
    public String getFileName() {
        if (file != null) {
            return file.getName();
        }

        int index = url.indexOf('/');
        if (index >= 0) return url.substring(index + 1);
        return url;
    }

    @NonNull
    @Override
    public String getPresentableUrl() {
        if (file != null) {
            return file.getPresentableUrl();
        }
        return toPresentableUrl(url);
    }

    @Override
    public boolean isValid() {
        return file != null;
    }

    private void refreshFile() {
        if (file != null && file.isValid()) {
            return;
        }

        VirtualFileManager fileManager = VirtualFileManager.getInstance();
        VirtualFile fileByUrl = fileManager.findFileByUrl(url);
        if (fileByUrl == null && !isRefreshed) {
            isRefreshed = true;
            fileByUrl = fileManager.findFileByUrl(url);
        }

        if (fileByUrl != null && fileByUrl.isValid()) {
            file = fileByUrl;
        } else {
            file = null;
        }
    }
}

