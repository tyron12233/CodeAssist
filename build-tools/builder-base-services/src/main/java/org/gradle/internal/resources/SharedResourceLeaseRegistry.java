package org.gradle.internal.resources;

import com.google.common.collect.Maps;

import org.gradle.internal.work.LeaseHolder;

import java.util.Map;

public class SharedResourceLeaseRegistry extends AbstractResourceLockRegistry<String, ResourceLock> {
    private final Map<String, LeaseHolder> sharedResources = Maps.newConcurrentMap();
    private final ResourceLockCoordinationService coordinationService;

    public SharedResourceLeaseRegistry(ResourceLockCoordinationService coordinationService) {
        super(coordinationService);
        this.coordinationService = coordinationService;
    }

    public void registerSharedResource(String name, int leases) {
        sharedResources.put(name, new LeaseHolder(leases));
    }

    public ResourceLock getResourceLock(final String sharedResource) {
        String displayName = "lease for " + sharedResource;
        return new DefaultLease(displayName, coordinationService, this, sharedResources.get(sharedResource));
    }
}