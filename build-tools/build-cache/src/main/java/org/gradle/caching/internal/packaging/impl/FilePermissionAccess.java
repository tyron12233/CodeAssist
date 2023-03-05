package org.gradle.caching.internal.packaging.impl;

import org.gradle.internal.file.FileException;

import java.io.File;

public interface FilePermissionAccess {

    int getUnixMode(File f) throws FileException;

    void chmod(File file, int mode) throws FileException;
}