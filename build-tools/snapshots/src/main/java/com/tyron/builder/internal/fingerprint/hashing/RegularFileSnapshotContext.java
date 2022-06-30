package com.tyron.builder.internal.fingerprint.hashing;

import com.tyron.builder.internal.snapshot.RegularFileSnapshot;

import java.util.function.Supplier;

public interface RegularFileSnapshotContext {
    Supplier<String[]> getRelativePathSegments();

    RegularFileSnapshot getSnapshot();
}