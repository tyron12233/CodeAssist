package com.tyron.builder.internal.snapshot;

import com.google.common.hash.HashCode;
import com.tyron.builder.internal.file.FileMetadata;
import com.tyron.builder.internal.file.FileType;

import java.util.Optional;

/**
 * A snapshot of a regular file.
 *
 * The snapshot includes the content hash of the file and its metadata.
 */
public class RegularFileSnapshot extends AbstractFileSystemLocationSnapshot implements FileSystemLeafSnapshot {
    private final HashCode contentHash;
    private final FileMetadata metadata;

    public RegularFileSnapshot(String absolutePath, String name, HashCode contentHash, FileMetadata metadata) {
        super(absolutePath, name, metadata.getAccessType());
        this.contentHash = contentHash;
        this.metadata = metadata;
    }

    @Override
    public FileType getType() {
        return FileType.RegularFile;
    }

    @Override
    public HashCode getHash() {
        return contentHash;
    }

    // Used by the Maven caching client. Do not remove
    public FileMetadata getMetadata() {
        return metadata;
    }

    @Override
    public boolean isContentAndMetadataUpToDate(FileSystemLocationSnapshot other) {
        return isContentUpToDate(other) && metadata.equals(((RegularFileSnapshot) other).metadata);
    }

    @Override
    public boolean isContentUpToDate(FileSystemLocationSnapshot other) {
        if (!(other instanceof RegularFileSnapshot)) {
            return false;
        }
        return contentHash.equals(((RegularFileSnapshot) other).contentHash);
    }

    @Override
    public void accept(FileSystemLocationSnapshotVisitor visitor) {
        visitor.visitRegularFile(this);
    }

    @Override
    public <T> T accept(FileSystemLocationSnapshotTransformer<T> transformer) {
        return transformer.visitRegularFile(this);
    }

    @Override
    public Optional<FileSystemNode> invalidate(VfsRelativePath targetPath, CaseSensitivity caseSensitivity, SnapshotHierarchy.NodeDiffListener diffListener) {
        diffListener.nodeRemoved(this);
        return Optional.empty();
    }

    @Override
    public String toString() {
        return String.format("%s@%s/%s", super.toString(), getHash(), getName());
    }
}