package com.tyron.builder.api.internal.fingerprint.hashing;

import com.tyron.builder.api.internal.snapshot.RegularFileSnapshot;

import java.util.function.Supplier;

public interface RegularFileSnapshotContext {
    Supplier<String[]> getRelativePathSegments();

    RegularFileSnapshot getSnapshot();
}