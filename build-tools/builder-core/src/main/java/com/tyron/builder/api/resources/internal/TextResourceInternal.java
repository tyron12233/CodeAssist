package com.tyron.builder.api.resources.internal;

import com.tyron.builder.api.resources.TextResource;
import com.tyron.builder.api.tasks.Internal;
import com.tyron.builder.internal.resource.Resource;

public interface TextResourceInternal extends TextResource, Resource {
    @Internal
    @Override
    String getDisplayName();
}
