package com.tyron.builder.api.internal.nativeintegration.services;

import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.FileMetadata;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;

import java.io.File;

public abstract class FileSystems {

    private static final FileSystem DEFAULT = new FileSystem() {
        @Override
        public boolean isCaseSensitive() {
            return false;
        }

        @Override
        public boolean canCreateSymbolicLink() {
            return false;
        }

        @Override
        public void createSymbolicLink(File link, File target) throws FileException {

        }

        @Override
        public boolean isSymlink(File suspect) {
            return false;
        }

        @Override
        public void chmod(File file, int mode) throws FileException {

        }

        @Override
        public int getUnixMode(File f) throws FileException {
            return 0;
        }

        @Override
        public FileMetadata stat(File f) throws FileException {
            return null;
        }
    };

    public static FileSystem getDefault() {
        return DEFAULT;
    }
}