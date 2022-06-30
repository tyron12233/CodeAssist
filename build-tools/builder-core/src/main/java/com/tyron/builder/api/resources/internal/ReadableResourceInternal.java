package com.tyron.builder.api.resources.internal;

import com.tyron.builder.api.resources.ReadableResource;
import com.tyron.builder.api.resources.Resource;

import java.io.File;

public interface ReadableResourceInternal extends ReadableResource, Resource {
    File getBackingFile();
}
