package com.tyron.builder.api.internal.artifacts.dsl.dependencies;

import com.tyron.builder.internal.resource.TextResource;

import java.net.URI;

public interface UriTextResourceLoader {
    TextResource getResource(URI source);
}