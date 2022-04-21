package com.tyron.builder.internal.execution.history.impl;

import com.google.common.collect.Interner;
import com.google.common.hash.HashCode;
import com.tyron.builder.internal.file.FileMetadata;
import com.tyron.builder.internal.file.impl.DefaultFileMetadata;
import com.tyron.builder.internal.serialize.Decoder;
import com.tyron.builder.internal.serialize.Encoder;
import com.tyron.builder.internal.serialize.Serializer;
import com.tyron.builder.internal.snapshot.CompositeFileSystemSnapshot;
import com.tyron.builder.internal.snapshot.DirectorySnapshot;
import com.tyron.builder.internal.snapshot.FileSystemLocationSnapshot;
import com.tyron.builder.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.internal.snapshot.MissingFileSnapshot;
import com.tyron.builder.internal.snapshot.PathUtil;
import com.tyron.builder.internal.snapshot.RegularFileSnapshot;
import com.tyron.builder.internal.snapshot.RootTrackingFileSystemSnapshotHierarchyVisitor;
import com.tyron.builder.internal.snapshot.SnapshotVisitResult;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;

public class FileSystemSnapshotSerializer implements Serializer<FileSystemSnapshot> {
    private enum EntryType {
        DIR_OPEN,
        REGULAR_FILE,
        MISSING,
        DIR_CLOSE,
        END
    }

    private final Interner<String> stringInterner;

    public FileSystemSnapshotSerializer(Interner<String> stringInterner) {
        this.stringInterner = stringInterner;
    }

    @Override
    public FileSystemSnapshot read(Decoder decoder) throws Exception {
        SnapshotStack stack = new SnapshotStack();
        stack.push();
        Deque<String> pathTracker = new ArrayDeque<>();
        while (true) {
            EntryType type = readEntryType(decoder);
            if (type == EntryType.END) {
                break;
            }
            if (type != EntryType.DIR_CLOSE) {
                String path = decoder.readString();
                String internedPath = stringInterner.intern(path);
                pathTracker.addLast(internedPath);
                if (type == EntryType.DIR_OPEN) {
                    stack.push();
                    continue;
                }
            }
            String internedAbsolutePath;
            String internedName;
            String path = pathTracker.removeLast();
            if (pathTracker.isEmpty()) {
                internedAbsolutePath = path;
                internedName = stringInterner.intern(PathUtil.getFileName(internedAbsolutePath));
            } else {
                internedAbsolutePath = stringInterner.intern(toAbsolutePath(pathTracker, path));
                internedName = path;
            }
            FileMetadata.AccessType accessType = readAccessType(decoder);
            switch (type) {
                case REGULAR_FILE:
                    HashCode contentHash = readHashCode(decoder);
                    long lastModified = decoder.readSmallLong();
                    long length = decoder.readSmallLong();
                    stack.add(new RegularFileSnapshot(internedAbsolutePath, internedName, contentHash, DefaultFileMetadata
                            .file(lastModified, length, accessType)));
                    break;
                case MISSING:
                    stack.add(new MissingFileSnapshot(internedAbsolutePath, internedName, accessType));
                    break;
                case DIR_CLOSE:
                    HashCode merkleHash = readHashCode(decoder);
                    List<FileSystemLocationSnapshot> children = stack.pop();
                    stack.add(new DirectorySnapshot(internedAbsolutePath, internedName, accessType, merkleHash, children));
                    break;
                default:
                    throw new AssertionError();
            }
        }
        return CompositeFileSystemSnapshot.of(stack.pop());
    }

    @Override
    public void write(Encoder encoder, FileSystemSnapshot value) throws Exception {
        value.accept(new RootTrackingFileSystemSnapshotHierarchyVisitor() {
            @Override
            public void enterDirectory(DirectorySnapshot directorySnapshot, boolean isRoot) {
                try {
                    writeEntryType(encoder, EntryType.DIR_OPEN);
                    writePath(encoder, isRoot, directorySnapshot);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public SnapshotVisitResult visitEntry(FileSystemLocationSnapshot snapshot, boolean isRoot) {
                snapshot.accept(new FileSystemLocationSnapshot.FileSystemLocationSnapshotVisitor() {
                    @Override
                    public void visitRegularFile(RegularFileSnapshot fileSnapshot) {
                        try {
                            writeEntryType(encoder, EntryType.REGULAR_FILE);
                            writePath(encoder, isRoot, fileSnapshot);
                            writeAccessType(encoder, fileSnapshot.getAccessType());
                            writeHashCode(encoder, fileSnapshot.getHash());
                            FileMetadata metadata = fileSnapshot.getMetadata();
                            encoder.writeSmallLong(metadata.getLastModified());
                            encoder.writeSmallLong(metadata.getLength());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }

                    @Override
                    public void visitMissing(MissingFileSnapshot missingSnapshot) {
                        try {
                            writeEntryType(encoder, EntryType.MISSING);
                            writePath(encoder, isRoot, missingSnapshot);
                            writeAccessType(encoder, missingSnapshot.getAccessType());
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                });
                return SnapshotVisitResult.CONTINUE;
            }

            @Override
            public void leaveDirectory(DirectorySnapshot directorySnapshot, boolean isRoot) {
                try {
                    writeEntryType(encoder, EntryType.DIR_CLOSE);
                    writeAccessType(encoder, directorySnapshot.getAccessType());
                    writeHashCode(encoder, directorySnapshot.getHash());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        });
        encoder.writeByte((byte) EntryType.END.ordinal());
    }

    private static void writePath(Encoder encoder, boolean isRoot, FileSystemLocationSnapshot snapshot) throws IOException {
        encoder.writeString(isRoot ? snapshot.getAbsolutePath() : snapshot.getName());
    }

    private static EntryType readEntryType(Decoder decoder) throws IOException {
        return EntryType.values()[decoder.readByte()];
    }

    private static void writeEntryType(Encoder encoder, EntryType type) throws IOException {
        encoder.writeByte((byte) type.ordinal());
    }

    private static FileMetadata.AccessType readAccessType(Decoder decoder) throws IOException {
        return FileMetadata.AccessType.values()[decoder.readByte()];
    }

    private static void writeAccessType(Encoder encoder, FileMetadata.AccessType accessType) throws IOException {
        encoder.writeByte((byte) accessType.ordinal());
    }

    private static HashCode readHashCode(Decoder decoder) throws IOException {
        return HashCode.fromBytes(decoder.readBinary());
    }

    private static void writeHashCode(Encoder encoder, HashCode hashCode) throws IOException {
        encoder.writeBinary(hashCode.asBytes());
    }

    private static class SnapshotStack {
        private final Deque<List<FileSystemLocationSnapshot>> stack = new ArrayDeque<>();

        public void push() {
            stack.addLast(new ArrayList<>());
        }

        public void add(FileSystemLocationSnapshot entry) {
            List<FileSystemLocationSnapshot> current = stack.peekLast();
            if (current == null) {
                throw new IllegalStateException("Stack empty");
            }
            current.add(entry);
        }

        public List<FileSystemLocationSnapshot> pop() {
            List<FileSystemLocationSnapshot> popped = stack.pollLast();
            if (popped == null) {
                throw new IllegalStateException("Stack empty");
            }
            return popped;
        }

        public boolean isEmpty() {
            return stack.isEmpty();
        }
    }

    private static String toAbsolutePath(Collection<String> parents, String fileName) {
        int length = fileName.length() + parents.size()  + parents.stream()
                .mapToInt(String::length)
                .sum();
        StringBuilder buffer = new StringBuilder(length);
        for (String parent : parents) {
            buffer.append(parent);
            buffer.append(File.separatorChar);
        }
        buffer.append(fileName);
        return buffer.toString();
    }
}