package com.tyron.builder.api.internal.execution.fingerprint;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.fingerprint.CurrentFileCollectionFingerprint;
import com.tyron.builder.api.internal.fingerprint.FileCollectionFingerprint;
import com.tyron.builder.api.internal.snapshot.FileSystemSnapshot;
import com.tyron.builder.api.tasks.FileNormalizer;

import org.jetbrains.annotations.Nullable;

public interface FileCollectionFingerprinter {
    /**
     * The type used to refer to this fingerprinter in the {@link FileCollectionFingerprinterRegistry}.
     */
    Class<? extends FileNormalizer> getRegisteredType();

    /**
     * Creates a fingerprint of the contents of the given collection.
     */
    CurrentFileCollectionFingerprint fingerprint(FileCollection files);

    /**
     * Creates a fingerprint from the snapshot of a file collection.
     */
    CurrentFileCollectionFingerprint fingerprint(FileSystemSnapshot snapshot, @Nullable FileCollectionFingerprint previousFingerprint);

    /**
     * Returns an empty fingerprint.
     */
    CurrentFileCollectionFingerprint empty();
}