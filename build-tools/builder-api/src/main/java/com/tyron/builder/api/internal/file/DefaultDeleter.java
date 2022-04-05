package com.tyron.builder.api.internal.file;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;

public class DefaultDeleter implements Deleter {
    @Override
    public boolean deleteRecursively(File target) throws IOException {
        return deleteRecursively(target, false);
    }

    @Override
    public boolean deleteRecursively(File target, boolean followSymlinks) throws IOException {
        if (target == null) {
            return false;
        }

        if (target.isFile()) {
            return target.delete();
        }

        FileUtils.deleteDirectory(target);
        return true;
    }

    @Override
    public boolean ensureEmptyDirectory(File target) throws IOException {
        return ensureEmptyDirectory(target, false);
    }

    @Override
    public boolean ensureEmptyDirectory(File target, boolean followSymlinks) throws IOException {
        FileUtils.cleanDirectory(target);
        return true;
    }

    @Override
    public boolean delete(File target) throws IOException {
        return target.delete();
    }
}
