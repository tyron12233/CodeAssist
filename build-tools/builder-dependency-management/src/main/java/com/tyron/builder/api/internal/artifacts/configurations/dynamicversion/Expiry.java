package com.tyron.builder.api.internal.artifacts.configurations.dynamicversion;

import java.time.Duration;

public interface Expiry {
    boolean isMustCheck();

    Duration getKeepFor();
}
