package org.gradle.internal.service.scopes;

import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.snapshot.impl.DefaultValueSnapshotter;
import org.gradle.internal.state.ManagedFactoryRegistry;

public class WorkerSharedBuildSessionScopeServices {

    DefaultValueSnapshotter createValueSnapshotter(ClassLoaderHierarchyHasher classLoaderHierarchyHasher, ManagedFactoryRegistry managedFactoryRegistry) {
        return new DefaultValueSnapshotter(classLoaderHierarchyHasher, managedFactoryRegistry);
    }
}
