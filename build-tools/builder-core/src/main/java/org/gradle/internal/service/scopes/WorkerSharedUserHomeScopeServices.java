package org.gradle.internal.service.scopes;

import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.snapshot.impl.DefaultIsolatableFactory;
import org.gradle.internal.snapshot.impl.DefaultValueSnapshotter;
import org.gradle.internal.state.ManagedFactoryRegistry;

public class WorkerSharedUserHomeScopeServices {

    IsolatableFactory createIsolatableFactory(
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            ManagedFactoryRegistry managedFactoryRegistry
    ) {
        return new DefaultIsolatableFactory(classLoaderHierarchyHasher, managedFactoryRegistry);
    }
}
