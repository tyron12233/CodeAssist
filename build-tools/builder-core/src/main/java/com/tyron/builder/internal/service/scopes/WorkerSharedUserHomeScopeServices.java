package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.snapshot.impl.DefaultValueSnapshotter;
import com.tyron.builder.internal.state.ManagedFactoryRegistry;

public class WorkerSharedUserHomeScopeServices {

    DefaultValueSnapshotter createValueSnapshotter(ClassLoaderHierarchyHasher classLoaderHierarchyHasher, ManagedFactoryRegistry managedFactoryRegistry) {
        return new DefaultValueSnapshotter(classLoaderHierarchyHasher, managedFactoryRegistry);
    }
}
