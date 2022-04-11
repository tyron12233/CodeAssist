package com.tyron.builder.api.internal.snapshot;

import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.hash.HashCode;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class SnapshotUtil {

    public static Map<String, FileSystemLocationSnapshot> index(FileSystemSnapshot snapshot) {
        HashMap<String, FileSystemLocationSnapshot> index = new HashMap<>();
        snapshot.accept(entrySnapshot -> {
            index.put(entrySnapshot.getAbsolutePath(), entrySnapshot);
            return SnapshotVisitResult.CONTINUE;
        });
        return index;
    }

    public static Map<String, FileSystemLocationSnapshot> rootIndex(FileSystemSnapshot snapshot) {
        HashMap<String, FileSystemLocationSnapshot> index = new HashMap<>();
        snapshot.accept(entrySnapshot -> {
            index.put(entrySnapshot.getAbsolutePath(), entrySnapshot);
            return SnapshotVisitResult.SKIP_SUBTREE;
        });
        return index;
    }

    public static <T extends FileSystemNode> Optional<MetadataSnapshot> getMetadataFromChildren(ChildMap<T> children, VfsRelativePath targetPath, CaseSensitivity caseSensitivity, Supplier<Optional<MetadataSnapshot>> noChildFoundResult) {
        return children.withNode(targetPath, caseSensitivity, new ChildMap.NodeHandler<T, Optional<MetadataSnapshot>>() {
            @Override
            public Optional<MetadataSnapshot> handleAsDescendantOfChild(VfsRelativePath pathInChild, T child) {
                return child.getSnapshot(pathInChild, caseSensitivity);
            }

            @Override
            public Optional<MetadataSnapshot> handleAsAncestorOfChild(String childPath, T child) {
                return noChildFoundResult.get();
            }

            @Override
            public Optional<MetadataSnapshot> handleExactMatchWithChild(T child) {
                return child.getSnapshot();
            }

            @Override
            public Optional<MetadataSnapshot> handleUnrelatedToAnyChild() {
                return noChildFoundResult.get();
            }
        });
    }

    public static <T extends FileSystemNode> Optional<FileSystemNode> getChild(ChildMap<T> children, VfsRelativePath targetPath, CaseSensitivity caseSensitivity) {
        return children.withNode(targetPath, caseSensitivity, new ChildMap.NodeHandler<T, Optional<FileSystemNode>>() {
            @Override
            public Optional<FileSystemNode> handleAsDescendantOfChild(VfsRelativePath pathInChild, T child) {
                return child.getNode(pathInChild, caseSensitivity);
            }

            @Override
            public Optional<FileSystemNode> handleAsAncestorOfChild(String childPath, T child) {
                return Optional.of(child);
            }

            @Override
            public Optional<FileSystemNode> handleExactMatchWithChild(T child) {
                return Optional.of(child);
            }

            @Override
            public Optional<FileSystemNode> handleUnrelatedToAnyChild() {
                return Optional.empty();
            }
        });
    }

    public static ImmutableMultimap<String, HashCode> getRootHashes(FileSystemSnapshot roots) {
        if (roots == FileSystemSnapshot.EMPTY) {
            return ImmutableMultimap.of();
        }
        ImmutableMultimap.Builder<String, HashCode> builder = ImmutableListMultimap.builder();
        roots.accept(snapshot -> {
            builder.put(snapshot.getAbsolutePath(), snapshot.getHash());
            return SnapshotVisitResult.SKIP_SUBTREE;
        });
        return builder.build();
    }
}
