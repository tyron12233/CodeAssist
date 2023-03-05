package org.gradle.api.resources.internal;

import org.gradle.api.resources.ReadableResource;
import org.gradle.api.resources.Resource;

import java.io.File;

public interface ReadableResourceInternal extends ReadableResource, Resource {
    File getBackingFile();
}
