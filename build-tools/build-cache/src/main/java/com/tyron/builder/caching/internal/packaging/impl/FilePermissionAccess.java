package com.tyron.builder.caching.internal.packaging.impl;

import com.tyron.builder.internal.file.FileException;

import java.io.File;

public interface FilePermissionAccess {

    int getUnixMode(File f) throws FileException;

    void chmod(File file, int mode) throws FileException;
}