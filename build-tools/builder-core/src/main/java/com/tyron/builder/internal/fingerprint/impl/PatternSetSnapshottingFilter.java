package com.tyron.builder.internal.fingerprint.impl;

import com.google.common.collect.Iterables;
import com.tyron.builder.api.Describable;
import com.tyron.builder.api.UncheckedIOException;
import com.tyron.builder.api.file.FileTreeElement;
import com.tyron.builder.api.file.RelativePath;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.file.Stat;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.SnapshottingFilter;
import com.tyron.builder.api.tasks.util.PatternSet;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

public class PatternSetSnapshottingFilter implements SnapshottingFilter {
    private final PatternSet patternSet;
    private final Stat stat;

    public PatternSetSnapshottingFilter(PatternSet patternSet, Stat stat) {
        this.stat = stat;
        this.patternSet = patternSet;
    }

    @Override
    public boolean isEmpty() {
        return patternSet.isEmpty();
    }

    @Override
    public FileSystemSnapshotPredicate getAsSnapshotPredicate() {
        Predicate<FileTreeElement> spec = patternSet.getAsSpec();
        return (snapshot, relativePath) -> spec.test(new LogicalFileTreeElement(snapshot, relativePath, stat));
    }

    @Override
    public DirectoryWalkerPredicate getAsDirectoryWalkerPredicate() {
        Predicate<FileTreeElement> spec = patternSet.getAsSpec();
        return (Path path, String name, boolean isDirectory, Iterable<String> relativePath) ->
                spec.test(new PathBackedFileTreeElement(path, name, isDirectory, relativePath, stat));
    }

    /**
     * Adapts a {@link FileSystemLocationSnapshot} to the {@link FileTreeElement} interface, e.g. to allow
     * passing it to a {@link PatternSet} for filtering.
     */
    private static class LogicalFileTreeElement implements FileTreeElement, Describable {
        private final Iterable<String> relativePathIterable;
        private final Stat stat;
        private final FileSystemLocationSnapshot snapshot;
        private RelativePath relativePath;
        private File file;

        public LogicalFileTreeElement(FileSystemLocationSnapshot snapshot, Iterable<String> relativePathIterable, Stat stat) {
            this.snapshot = snapshot;
            this.relativePathIterable = relativePathIterable;
            this.stat = stat;
        }

        @Override
        public String getDisplayName() {
            return "file '" + getFile() + "'";
        }

        @Override
        public File getFile() {
            if (file == null) {
                file = new File(snapshot.getAbsolutePath());
            }
            return file;
        }

        @Override
        public boolean isDirectory() {
            return snapshot.getType() == FileType.Directory;
        }

        @Override
        public long getLastModified() {
            return getFile().lastModified();
        }

        @Override
        public long getSize() {
            return getFile().length();
        }

        @Override
        public InputStream open() {
            try {
                return FileUtils.openInputStream(getFile());
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
            return getRelativePath().getLastName();
        }

        @Override
        public String getPath() {
            return getRelativePath().getPathString();
        }

        @Override
        public RelativePath getRelativePath() {
            if (relativePath == null) {
                relativePath = new RelativePath(!isDirectory(), Iterables.toArray(relativePathIterable, String.class));
            }
            return relativePath;
        }

        @Override
        public int getMode() {
            return stat.getUnixMode(getFile());
        }
    }

    private static class PathBackedFileTreeElement implements FileTreeElement {
        private final Path path;
        private final String name;
        private final boolean isDirectory;
        private final Iterable<String> relativePath;
        private final Stat stat;

        public PathBackedFileTreeElement(Path path, String name, boolean isDirectory, Iterable<String> relativePath, Stat stat) {
            this.path = path;
            this.name = name;
            this.isDirectory = isDirectory;
            this.relativePath = relativePath;
            this.stat = stat;
        }

        @Override
        public File getFile() {
            return path.toFile();
        }

        @Override
        public boolean isDirectory() {
            return isDirectory;
        }

        @Override
        public long getLastModified() {
            return getFile().lastModified();
        }

        @Override
        public long getSize() {
            return getFile().length();
        }

        @Override
        public InputStream open() {
            try {
                return Files.newInputStream(path);
            } catch (IOException e) {
                throw UncheckedException.throwAsUncheckedException(e);
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
            return name;
        }

        @Override
        public String getPath() {
            return getRelativePath().getPathString();
        }

        @Override
        public RelativePath getRelativePath() {
            return new RelativePath(!isDirectory, Iterables.toArray(relativePath, String.class));
        }

        @Override
        public int getMode() {
            return stat.getUnixMode(path.toFile());
        }
    }
}