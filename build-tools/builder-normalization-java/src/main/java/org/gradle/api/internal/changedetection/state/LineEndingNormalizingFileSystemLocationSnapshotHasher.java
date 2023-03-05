package org.gradle.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import org.gradle.internal.file.FileType;
import org.gradle.internal.fingerprint.LineEndingSensitivity;
import org.gradle.internal.fingerprint.hashing.FileSystemLocationSnapshotHasher;
import org.gradle.internal.snapshot.FileSystemLocationSnapshot;
import org.gradle.internal.io.IoSupplier;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A {@link FileSystemLocationSnapshotHasher} that normalizes line endings while hashing the file.  It detects whether a file is text or binary and only
 * normalizes line endings for text files.  If a file is detected to be binary, we fall back to the existing non-normalized hash.
 *
 * See {@link LineEndingNormalizingInputStreamHasher}.
 */
public class LineEndingNormalizingFileSystemLocationSnapshotHasher implements FileSystemLocationSnapshotHasher {
    private final FileSystemLocationSnapshotHasher delegate;
    private final LineEndingNormalizingInputStreamHasher hasher;

    private LineEndingNormalizingFileSystemLocationSnapshotHasher(FileSystemLocationSnapshotHasher delegate) {
        this.delegate = delegate;
        this.hasher = new LineEndingNormalizingInputStreamHasher();
    }

    public static FileSystemLocationSnapshotHasher wrap(FileSystemLocationSnapshotHasher delegate, LineEndingSensitivity lineEndingSensitivity) {
        switch (lineEndingSensitivity) {
            case DEFAULT:
                return delegate;
            case NORMALIZE_LINE_ENDINGS:
                return new LineEndingNormalizingFileSystemLocationSnapshotHasher(delegate);
            default:
                throw new IllegalArgumentException();
        }
    }

    @Override
    public void appendConfigurationToHasher(Hasher hasher) {
        delegate.appendConfigurationToHasher(hasher);
        hasher.putString(getClass().getName(), StandardCharsets.UTF_8);
    }

    @Nullable
    @Override
    public HashCode hash(FileSystemLocationSnapshot snapshot) throws IOException {
        return hashContent(snapshot)
                .orElseGet(IoSupplier.wrap(() -> delegate.hash(snapshot)));
    }

    private Optional<HashCode> hashContent(FileSystemLocationSnapshot snapshot) throws IOException {
        return snapshot.getType() == FileType.RegularFile ? hasher.hashContent(new File(snapshot.getAbsolutePath())) : Optional.empty();
    }
}