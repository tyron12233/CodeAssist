package org.gradle.api.resources.internal;

import org.gradle.api.resources.TextResource;
import org.gradle.api.tasks.Internal;
import org.gradle.internal.resource.Resource;

public interface TextResourceInternal extends TextResource, Resource {
    @Internal
    @Override
    String getDisplayName();
}
