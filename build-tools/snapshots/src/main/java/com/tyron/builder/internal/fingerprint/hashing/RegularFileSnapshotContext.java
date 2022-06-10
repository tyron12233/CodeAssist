package org.gradle.internal.fingerprint.hashing;

import org.gradle.internal.snapshot.RegularFileSnapshot;

import java.util.function.Supplier;

public interface RegularFileSnapshotContext {
    Supplier<String[]> getRelativePathSegments();

    RegularFileSnapshot getSnapshot();
}