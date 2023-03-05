package org.gradle.plugins.ide.idea.model;

import java.io.File;

/**
 * A Path that keeps the reference to the File
 */
public class FilePath extends Path {

    private final File file;

    public FilePath(File file, String url, String canonicalUrl, String relPath) {
        super(url, canonicalUrl, relPath);
        this.file = file;
    }

    public File getFile() {
        return file;
    }
}