package com.tyron.builder.api.internal.changedetection.state;

import com.tyron.builder.internal.fingerprint.hashing.RegularFileSnapshotContext;
import com.tyron.builder.internal.snapshot.RegularFileSnapshot;

import java.util.function.Supplier;

public class DefaultRegularFileSnapshotContext implements RegularFileSnapshotContext {
    private final Supplier<String[]> relativePathSegmentSupplier;
    private final RegularFileSnapshot snapshot;

    public DefaultRegularFileSnapshotContext(Supplier<String[]> relativePathSegmentSupplier, RegularFileSnapshot snapshot) {
        this.relativePathSegmentSupplier = relativePathSegmentSupplier;
        this.snapshot = snapshot;
    }

    @Override
    public Supplier<String[]> getRelativePathSegments() {
        return relativePathSegmentSupplier;
    }

    @Override
    public RegularFileSnapshot getSnapshot() {
        return snapshot;
    }
}