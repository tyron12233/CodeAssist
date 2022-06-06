package com.tyron.builder.internal.execution.history.changes;

import com.google.common.collect.ImmutableSortedMap;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.file.FileSystemLocation;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.file.FileType;
import com.tyron.builder.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.api.provider.Provider;
import com.tyron.builder.api.tasks.incremental.InputFileDetails;
import com.tyron.builder.work.ChangeType;
import com.tyron.builder.work.FileChange;

import java.io.File;
import java.util.Objects;
import java.util.stream.Stream;

public class NonIncrementalInputChanges implements InputChangesInternal {
    private final ImmutableSortedMap<String, CurrentFileCollectionFingerprint> currentInputs;
    private final IncrementalInputProperties incrementalInputProperties;

    public NonIncrementalInputChanges(ImmutableSortedMap<String, CurrentFileCollectionFingerprint> currentInputs, IncrementalInputProperties incrementalInputProperties) {
        this.currentInputs = currentInputs;
        this.incrementalInputProperties = incrementalInputProperties;
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @Override
    public Iterable<FileChange> getFileChanges(FileCollection parameter) {
        return getObjectFileChanges(parameter);
    }

    @Override
    public Iterable<FileChange> getFileChanges(Provider<? extends FileSystemLocation> parameter) {
        return getObjectFileChanges(parameter);
    }

    public Iterable<FileChange> getObjectFileChanges(Object parameter) {
        CurrentFileCollectionFingerprint currentFileCollectionFingerprint = currentInputs.get(incrementalInputProperties.getPropertyNameFor(parameter));
        return () -> getAllFileChanges(currentFileCollectionFingerprint).iterator();
    }

    @Override
    public Iterable<InputFileDetails> getAllFileChanges() {
        Iterable<FileChange> changes = () -> currentInputs.values().stream().flatMap(NonIncrementalInputChanges::getAllFileChanges).iterator();
        return Cast.uncheckedNonnullCast(changes);
    }

    private static Stream<FileChange> getAllFileChanges(CurrentFileCollectionFingerprint currentFileCollectionFingerprint) {
        return currentFileCollectionFingerprint.getFingerprints().entrySet().stream()
                .map(entry -> new RebuildFileChange(entry.getKey(), entry.getValue().getNormalizedPath(), entry.getValue().getType()));
    }

    private static class RebuildFileChange implements FileChange, InputFileDetails {
        private final String path;
        private final String normalizedPath;
        private final FileType fileType;

        public RebuildFileChange(String path, String normalizedPath, FileType fileType) {
            this.path = path;
            this.normalizedPath = normalizedPath;
            this.fileType = fileType;
        }

        @Override
        public File getFile() {
            return new File(path);
        }

        @Override
        public ChangeType getChangeType() {
            return ChangeType.ADDED;
        }

        @Override
        public com.tyron.builder.api.file.FileType getFileType() {
            return DefaultFileChange.toPublicFileType(fileType);
        }

        @Override
        public String getNormalizedPath() {
            return normalizedPath;
        }

        @Override
        public boolean isAdded() {
            return false;
        }

        @Override
        public boolean isModified() {
            return false;
        }

        @Override
        public boolean isRemoved() {
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            RebuildFileChange that = (RebuildFileChange) o;
            return path.equals(that.path) &&
                   normalizedPath.equals(that.normalizedPath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, normalizedPath);
        }

        @Override
        public String toString() {
            return "Input file " + path + " added for rebuild.";
        }
    }
}