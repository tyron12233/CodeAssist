package com.tyron.builder.util.internal;

import com.tyron.builder.StartParameter;
import com.tyron.builder.internal.service.scopes.Scopes;
import com.tyron.builder.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.BuildSession.class)
public class BuildCommencedTimeProvider {
    private final long fixedTime;

    public BuildCommencedTimeProvider(StartParameter startParameter) {
        String offsetStr = startParameter.getSystemPropertiesArgs().get("com.tyron.builder.internal.test.clockoffset");
        long offset = offsetStr != null ? Long.parseLong(offsetStr) : 0;
        fixedTime = offset + System.currentTimeMillis();
    }

    public long getCurrentTime() {
        return fixedTime;
    }
}
