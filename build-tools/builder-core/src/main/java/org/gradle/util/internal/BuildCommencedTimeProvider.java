package org.gradle.util.internal;

import org.gradle.StartParameter;
import org.gradle.internal.service.scopes.Scopes;
import org.gradle.internal.service.scopes.ServiceScope;

@ServiceScope(Scopes.BuildSession.class)
public class BuildCommencedTimeProvider {
    private final long fixedTime;

    public BuildCommencedTimeProvider(StartParameter startParameter) {
        String offsetStr = startParameter.getSystemPropertiesArgs().get("org.gradle.internal.test.clockoffset");
        long offset = offsetStr != null ? Long.parseLong(offsetStr) : 0;
        fixedTime = offset + System.currentTimeMillis();
    }

    public long getCurrentTime() {
        return fixedTime;
    }
}
