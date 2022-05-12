package com.tyron.builder.groovy.scripts;

import com.tyron.builder.internal.resource.CachingTextResource;
import com.tyron.builder.internal.resource.TextResource;

public class CachingScriptSource extends DelegatingScriptSource {
    private final TextResource resource;

    public static ScriptSource of(ScriptSource source) {
        if (source.getResource().isContentCached()) {
            return source;
        }
        return new CachingScriptSource(source);
    }

    private CachingScriptSource(ScriptSource source) {
        super(source);
        resource = new CachingTextResource(source.getResource());
    }

    @Override
    public TextResource getResource() {
        return resource;
    }
}
