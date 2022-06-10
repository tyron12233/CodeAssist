package org.gradle.api.internal.resources;

import org.gradle.api.resources.internal.ReadableResourceInternal;

public interface ResourceResolver {
    ReadableResourceInternal resolveResource(Object path);
}
