package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;

public abstract class AbstractFileTreeElement implements FileTreeElement {
    private final Chmod chmod;

    public abstract String getDisplayName();

    protected AbstractFileTreeElement(Chmod chmod) {
        this.chmod = chmod;
    }

    protected Chmod getChmod() {
        return chmod;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

    @Override
    public String getName() {
        return getRelativePath().getLastName();
    }

    @Override
    public String getPath() {
        return getRelativePath().getPathString();
    }

    @Override
    public void copyTo(OutputStream output) {
        try {
            InputStream inputStream = open();
            try {
                IOUtils.copyLarge(inputStream, output);
            } finally {
                inputStream.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean copyTo(File target) {
        validateTimeStamps();
        try {
            if (isDirectory()) {
                boolean mkdirs = target.mkdirs();
                if (!mkdirs) {
                    throw new IOException("Failed to create parent directories for " + target);
                }
            } else {
                boolean mkdirs = target.getParentFile().mkdirs();
                if (!mkdirs) {
                    throw new IOException("Failed to create parent directories for: " + target.getParentFile());
                }
                copyFile(target);
            }
            chmod.chmod(target, getMode());
            return true;
        } catch (Exception e) {
            throw new CopyFileElementException(String.format("Could not copy %s to '%s'.", getDisplayName(), target), e);
        }
    }

    private void validateTimeStamps() {
        final long lastModified = getLastModified();
        if(lastModified < 0) {
            throw new RuntimeException(String.format("Invalid Timestamp %s for '%s'.", lastModified, getDisplayName()));
        }
    }

    private void copyFile(File target) throws IOException {
        FileOutputStream outputStream = new FileOutputStream(target);
        try {
            copyTo(outputStream);
        } finally {
            outputStream.close();
        }
    }

    @Override
    public int getMode() {
        return isDirectory()
                ? FileSystem.DEFAULT_DIR_MODE
                : FileSystem.DEFAULT_FILE_MODE;
    }

    private static class CopyFileElementException extends RuntimeException {
        CopyFileElementException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}