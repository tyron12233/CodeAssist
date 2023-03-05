package org.gradle.api.internal.artifacts.dsl.dependencies;

import org.gradle.internal.resource.TextResource;

import java.net.URI;

public interface UriTextResourceLoader {
    TextResource getResource(URI source);
}