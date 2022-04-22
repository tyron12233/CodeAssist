package com.tyron.builder.api.internal.changedetection.state;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.internal.fingerprint.hashing.RegularFileSnapshotContext;
import com.tyron.builder.internal.fingerprint.hashing.ResourceHasher;
import com.tyron.builder.internal.fingerprint.hashing.ZipEntryContext;
import com.tyron.builder.internal.io.IoSupplier;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A {@link ResourceHasher} that normalizes line endings while hashing the file.  It detects whether a file is text or binary and only
 * normalizes line endings for text files.  If a file is detected to be binary, we fall back to the existing non-normalized hash.
 *
 * See {@link LineEndingNormalizingInputStreamHasher}.
 */
public class LineEndingNormalizingResourceHasher implements ResourceHasher {
    private final ResourceHasher delegate;
    private final LineEndingNormalizingInputStreamHasher hasher;

    private LineEndingNormalizingResourceHasher(ResourceHasher delegate) {
        this.delegate = delegate;
        this.hasher = new LineEndingNormalizingInputStreamHasher();
    }

    public static ResourceHasher wrap(ResourceHasher delegate, LineEndingSensitivity lineEndingSensitivity) {
        switch (lineEndingSensitivity) {
            case DEFAULT:
                return delegate;
            case NORMALIZE_LINE_ENDINGS:
                return new LineEndingNormalizingResourceHasher(delegate);
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
    public HashCode hash(RegularFileSnapshotContext snapshotContext) throws IOException {
        return hasher.hashContent(new File(snapshotContext.getSnapshot().getAbsolutePath()))
                .orElseGet(IoSupplier.wrap(() -> delegate.hash(snapshotContext)));
    }

    @Nullable
    @Override
    public HashCode hash(ZipEntryContext zipEntryContext) throws IOException {
        return hashContent(zipEntryContext)
                .orElseGet(IoSupplier.wrap(() -> delegate.hash(zipEntryContext)));
    }

    private Optional<HashCode> hashContent(ZipEntryContext zipEntryContext) throws IOException {
        return zipEntryContext.getEntry().isDirectory() ? Optional.empty() : zipEntryContext.getEntry().withInputStream(hasher::hashContent);
    }
}