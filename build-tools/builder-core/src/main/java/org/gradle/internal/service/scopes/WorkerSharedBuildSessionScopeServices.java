package org.gradle.internal.service.scopes;

import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.snapshot.ValueSnapshotter;
import org.gradle.internal.snapshot.impl.DefaultValueSnapshotter;
import org.gradle.internal.snapshot.impl.ValueSnapshotterSerializerRegistry;
import org.gradle.internal.state.ManagedFactoryRegistry;

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
