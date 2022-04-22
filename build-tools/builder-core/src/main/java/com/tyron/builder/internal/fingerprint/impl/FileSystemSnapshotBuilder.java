package com.tyron.builder.internal.fingerprint.impl;

import static com.tyron.builder.internal.snapshot.DirectorySnapshotBuilder.EmptyDirectoryHandlingStrategy.INCLUDE_EMPTY_DIRS;

import com.google.common.base.Preconditions;
import com.google.common.collect.Interner;
import com.google.common.hash.HashCode;
import com.tyron.builder.internal.file.FileMetadata;
import com.tyron.builder.internal.file.FileMetadata.AccessType;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.snapshot.DirectorySnapshotBuilder;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.MerkleDirectorySnapshotBuilder;
import com.tyron.builder.internal.snapshot.RegularFileSnapshot;


import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;


public class FileSystemSnapshotBuilder {

    private final Interner<String> stringInterner;
    private final FileHasher fileHasher;
    private DirectoryBuilder rootDirectoryBuilder;
    private String rootPath;
    private String rootName;
    private RegularFileSnapshot rootFileSnapshot;

    public FileSystemSnapshotBuilder(Interner<String> stringInterner, FileHasher fileHasher) {
        this.stringInterner = stringInterner;
        this.fileHasher = fileHasher;
    }

    public void addDir(File dir, String[] segments) {
        checkNoRootFileSnapshot("directory", dir);
        DirectoryBuilder rootBuilder = getOrCreateRootDir(dir, segments);
        rootBuilder.addDir(segments, 0);
    }

    public void addFile(File file, String[] segments, String fileName, FileMetadata metadata) {
        checkNoRootFileSnapshot("another root file", file);
        HashCode contentHash = fileHasher.hash(file, metadata.getLength(), metadata.getLastModified());
        RegularFileSnapshot fileSnapshot = new RegularFileSnapshot(stringInterner.intern(file.getAbsolutePath()), fileName, contentHash, metadata);
        if (segments.length == 0) {
            rootFileSnapshot = fileSnapshot;
        } else {
            DirectoryBuilder rootDir = getOrCreateRootDir(file, segments);
            rootDir.addFile(segments, 0, fileSnapshot);
        }
    }

    private void checkNoRootFileSnapshot(String description, File file) {
        if (rootFileSnapshot != null) {
            throw new IllegalArgumentException(String.format("Cannot add %s '%s' for root file '%s'", description, file, rootFileSnapshot.getAbsolutePath()));
        }
    }

    private DirectoryBuilder getOrCreateRootDir(File dir, String[] segments) {
        if (rootDirectoryBuilder == null) {
            rootDirectoryBuilder = new DirectoryBuilder();
            Path rootDir = dir.toPath();
            for (String ignored : segments) {
                rootDir = rootDir.getParent();
            }
            rootPath = stringInterner.intern(rootDir.toAbsolutePath().toString());
            rootName = stringInterner.intern(rootDir.getFileName().toString());
        }
        return rootDirectoryBuilder;
    }

    public FileSystemSnapshot build() {
        if (rootFileSnapshot != null) {
            return rootFileSnapshot;
        }
        if (rootDirectoryBuilder == null) {
            return FileSystemSnapshot.EMPTY;
        }
        DirectorySnapshotBuilder builder = MerkleDirectorySnapshotBuilder.sortingRequired();
        rootDirectoryBuilder.accept(rootPath, rootName, builder);
        return Preconditions.checkNotNull(builder.getResult());
    }

    private class DirectoryBuilder {
        private final Map<String, DirectoryBuilder> subDirs = new HashMap<>();
        private final Map<String, RegularFileSnapshot> files = new HashMap<>();

        public void addFile(String[] segments, int offset, RegularFileSnapshot fileSnapshot) {
            if (segments.length == offset) {
                throw new IllegalStateException("A file cannot be in the same place as a directory: " + fileSnapshot.getAbsolutePath());
            }
            String currentSegment = stringInterner.intern(segments[offset]);

            if (segments.length == offset + 1) {
                if (subDirs.containsKey(currentSegment)) {
                    throw new IllegalStateException("A file cannot be added in the same place as a directory: " + fileSnapshot.getAbsolutePath());
                }
                files.put(currentSegment, fileSnapshot);
            } else {
                DirectoryBuilder subDir = getOrCreateSubDir(currentSegment);
                subDir.addFile(segments, offset + 1, fileSnapshot);
            }
        }

        public void addDir(String[] segments, int offset) {
            if (segments.length == offset) {
                return;
            }
            String currentSegment = stringInterner.intern(segments[offset]);
            DirectoryBuilder subDir = getOrCreateSubDir(currentSegment);
            subDir.addDir(segments, offset + 1);
        }

        private DirectoryBuilder getOrCreateSubDir(String currentSegment) {
            if (files.containsKey(currentSegment)) {
                RegularFileSnapshot fileSnapshot = files.get(currentSegment);
                throw new IllegalStateException("A file cannot be added in the same place as a directory:" + fileSnapshot.getAbsolutePath());
            }
            DirectoryBuilder subDir = subDirs.get(currentSegment);
            if (subDir == null) {
                subDir = new DirectoryBuilder();
                subDirs.put(currentSegment, subDir);
            }
            return subDir;
        }

        public void accept(String directoryPath, String directoryName, DirectorySnapshotBuilder builder) {
            builder.enterDirectory(determineAccessTypeForLocation(directoryPath), directoryPath, directoryName, DirectorySnapshotBuilder.EmptyDirectoryHandlingStrategy.INCLUDE_EMPTY_DIRS);
            for (Map.Entry<String, DirectoryBuilder> entry : subDirs.entrySet()) {
                String subDirName = entry.getKey();
                String subDirPath = stringInterner.intern(directoryPath + File.separatorChar + subDirName);
                entry.getValue().accept(subDirPath, subDirName, builder);
            }
            for (RegularFileSnapshot fileSnapshot : files.values()) {
                builder.visitLeafElement(fileSnapshot);
            }
            builder.leaveDirectory();
        }
    }

    private static AccessType determineAccessTypeForLocation(String absolutePath) {
        return AccessType.viaSymlink(Files.isSymbolicLink(Paths.get(absolutePath)));
    }
}