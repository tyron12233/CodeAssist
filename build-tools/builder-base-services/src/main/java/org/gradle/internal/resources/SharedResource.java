package org.gradle.internal.resources;

public interface SharedResource {
    /**
     * @return The maximum usage, or -1 when there is no limit.
     */
    int getMaxUsages();

    ResourceLock getResourceLock();
}