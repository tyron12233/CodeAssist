package com.tyron.builder.caching.internal.packaging.impl;

import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.TreeType;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class DefaultTarPackerFileSystemSupport implements TarPackerFileSystemSupport {
    private final Deleter deleter;

    public DefaultTarPackerFileSystemSupport(Deleter deleter) {
        this.deleter = deleter;
    }

    @Override
    public void ensureFileIsMissing(File entry) throws IOException {
        if (!makeDirectory(entry.getParentFile())) {
            // Make sure tree is removed if it exists already
            deleter.deleteRecursively(entry);
        }
    }

    @Override
    public void ensureDirectoryForTree(TreeType type, File root) throws IOException {
        switch (type) {
            case DIRECTORY:
                deleter.ensureEmptyDirectory(root);
                break;
            case FILE:
                if (!makeDirectory(root.getParentFile())) {
                    if (root.exists()) {
                        deleter.deleteRecursively(root);
                    }
                }
                break;
            default:
                throw new AssertionError();
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean makeDirectory(File target) throws IOException {
        if (target.isDirectory()) {
            return false;
        } else if (target.isFile()) {
            deleter.delete(target);
        }
        FileUtils.forceMkdir(target);
        return true;
    }
}