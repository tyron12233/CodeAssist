package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.api.internal.snapshot.ValueSnapshotter;
import com.tyron.builder.api.internal.snapshot.impl.DefaultValueSnapshotter;
import com.tyron.builder.api.internal.snapshot.impl.ValueSnapshotterSerializerRegistry;

import java.util.List;

public class WorkerSharedBuildSessionScopeServices {

    ValueSnapshotter createValueSnapshotter(
            List<ValueSnapshotterSerializerRegistry> valueSnapshotterSerializerRegistryList,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher
    ) {
        return new DefaultValueSnapshotter(
                valueSnapshotterSerializerRegistryList,
                classLoaderHierarchyHasher
        );
    }
}
