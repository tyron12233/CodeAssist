package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.file.RelativePath;

import java.io.File;
import java.io.Serializable;

public class RelativeFile implements Serializable {

    private final File file;
    private final RelativePath relativePath;

    public RelativeFile(File file, RelativePath relativePath) {
        this.file = file;
        this.relativePath = relativePath;
    }

    public File getFile() {
        return file;
    }

    public RelativePath getRelativePath() {
        return relativePath;
    }

    public File getBaseDir() {
        if (file == null || relativePath == null) {
            return null;
        }
        int relativeSegments = relativePath.getSegments().length;
        File parentFile = file;
        for (int i=0; i<relativeSegments; i++) {
            parentFile = parentFile.getParentFile();
        }
        return parentFile;
    }

}
