package com.tyron.builder.internal.snapshot;

import static com.tyron.builder.internal.snapshot.DirectorySnapshotBuilder.EmptyDirectoryHandlingStrategy.EXCLUDE_EMPTY_DIRS;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.tyron.builder.internal.file.FileMetadata.AccessType;
import com.tyron.builder.internal.hash.Hashes;

import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A builder for {@link DirectorySnapshot} instances.
 *
 * This implementation combines the hashes of the children of a directory into a single hash for the directory.
 * For the hash to be reproducible, the children must be sorted in a consistent order.
 * The implementation uses {@link FileSystemLocationSnapshot#BY_NAME} ordering.
 * If you already provide the children in sorted order, use {@link #noSortingRequired()} to avoid the overhead of sorting again.
 */
public class MerkleDirectorySnapshotBuilder implements DirectorySnapshotBuilder {
    private static final HashCode DIR_SIGNATURE = Hashes.signature("DIR");

    private final Deque<Directory> directoryStack = new ArrayDeque<>();
    private final boolean sortingRequired;
    private FileSystemLocationSnapshot result;

    public static DirectorySnapshotBuilder sortingRequired() {
        return new MerkleDirectorySnapshotBuilder(true);
    }

    public static DirectorySnapshotBuilder noSortingRequired() {
        return new MerkleDirectorySnapshotBuilder(false);
    }

    protected MerkleDirectorySnapshotBuilder(boolean sortingRequired) {
        this.sortingRequired = sortingRequired;
    }

    @Override
    public void enterDirectory(AccessType accessType, String absolutePath, String name, EmptyDirectoryHandlingStrategy emptyDirectoryHandlingStrategy) {
        directoryStack.addLast(new Directory(accessType, absolutePath, name, emptyDirectoryHandlingStrategy));
    }

    @Override
    public void visitLeafElement(FileSystemLeafSnapshot snapshot) {
        collectEntry(snapshot);
    }

    @Override
    public void visitDirectory(DirectorySnapshot directorySnapshot) {
        collectEntry(directorySnapshot);
    }

    @Override
    public FileSystemLocationSnapshot leaveDirectory() {
        FileSystemLocationSnapshot snapshot = directoryStack.removeLast().fold();
        if (snapshot != null) {
            collectEntry(snapshot);
        }
        return snapshot;
    }

    private void collectEntry(FileSystemLocationSnapshot snapshot) {
        Directory directory = directoryStack.peekLast();
        if (directory != null) {
            directory.collectEntry(snapshot);
        } else {
            assert result == null;
            result = snapshot;
        }
    }

    @Override
    public FileSystemLocationSnapshot getResult() {
        return result;
    }

    private class Directory {
        private final AccessType accessType;
        private final String absolutePath;
        private final String name;
        private final List<FileSystemLocationSnapshot> children;
        private final EmptyDirectoryHandlingStrategy emptyDirectoryHandlingStrategy;

        public Directory(AccessType accessType, String absolutePath, String name, EmptyDirectoryHandlingStrategy emptyDirectoryHandlingStrategy) {
            this.accessType = accessType;
            this.absolutePath = absolutePath;
            this.name = name;
            this.children = new ArrayList<>();
            this.emptyDirectoryHandlingStrategy = emptyDirectoryHandlingStrategy;
        }

        public void collectEntry(FileSystemLocationSnapshot snapshot) {
            children.add(snapshot);
        }

        @Nullable
        public DirectorySnapshot fold() {
            if (emptyDirectoryHandlingStrategy == EXCLUDE_EMPTY_DIRS && children.isEmpty()) {
                return null;
            }
            if (sortingRequired) {
                children.sort(FileSystemLocationSnapshot.BY_NAME);
            }
            Hasher hasher = Hashes.newHasher();
            Hashes.putHash(hasher, DIR_SIGNATURE);
            for (FileSystemLocationSnapshot child : children) {
                hasher.putString(child.getName(), StandardCharsets.UTF_8);
                Hashes.putHash(hasher, child.getHash());
            }
            return new DirectorySnapshot(absolutePath, name, accessType, hasher.hash(), children);
        }
    }
}