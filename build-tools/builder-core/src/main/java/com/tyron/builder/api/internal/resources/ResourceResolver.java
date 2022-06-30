package com.tyron.builder.api.internal.resources;

import com.tyron.builder.api.resources.internal.ReadableResourceInternal;

public interface ResourceResolver {
    ReadableResourceInternal resolveResource(Object path);
}
