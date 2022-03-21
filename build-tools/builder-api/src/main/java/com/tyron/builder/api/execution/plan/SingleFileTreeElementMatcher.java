package com.tyron.builder.api.execution.plan;

import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.api.internal.file.Stat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.function.Predicate;

public class SingleFileTreeElementMatcher {

    private final Stat stat;

    public SingleFileTreeElementMatcher(Stat stat) {
        this.stat = stat;
    }

    public boolean elementWithRelativePathMatches(Predicate<FileTreeElement> filter, File element, String relativePathString) {
        // A better solution for output files would be to record the type of the output file and then using this type here instead of looking at the disk.
        // Though that is more involved and as soon as the file has been produced, the right file type will be detected here as well.
        boolean elementIsFile = !element.isDirectory();
        RelativePath relativePath = RelativePath.parse(elementIsFile, relativePathString);
        if (!filter.test(new ReadOnlyFileTreeElement(element, relativePath, stat))) {
            return false;
        }
        // All parent paths need to match the spec as well, since this is how we implement the file system walking for file tree.
        RelativePath parentRelativePath = relativePath.getParent();
        File parentFile = element.getParentFile();
        while (parentRelativePath != null && parentRelativePath != RelativePath.EMPTY_ROOT) {
            if (!filter.test(new ReadOnlyFileTreeElement(parentFile, parentRelativePath, stat))) {
                return false;
            }
            parentRelativePath = parentRelativePath.getParent();
            parentFile = parentFile.getParentFile();
        }
        return true;
    }

    private static class ReadOnlyFileTreeElement implements FileTreeElement {
        private final File file;
        private final RelativePath relativePath;
        private final Stat stat;

        public ReadOnlyFileTreeElement(File file, RelativePath relativePath, Stat stat) {
            this.file = file;
            this.relativePath = relativePath;
            this.stat = stat;
        }

        @Override
        public File getFile() {
            return file;
        }

        @Override
        public boolean isDirectory() {
            return !relativePath.isFile();
        }

        @Override
        public long getLastModified() {
            return file.lastModified();
        }

        @Override
        public long getSize() {
            return file.length();
        }

        @Override
        public InputStream open() {
            try {
                return Files.newInputStream(file.toPath());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void copyTo(OutputStream output) {
            throw new UnsupportedOperationException("Copy to not supported for filters");
        }

        @Override
        public boolean copyTo(File target) {
            throw new UnsupportedOperationException("Copy to not supported for filters");
        }

        @Override
        public String getName() {
            return file.getName();
        }

        @Override
        public String getPath() {
            return relativePath.getPathString();
        }

        @Override
        public RelativePath getRelativePath() {
            return relativePath;
        }

        @Override
        public int getMode() {
            return stat.getUnixMode(file);
        }
    }
}