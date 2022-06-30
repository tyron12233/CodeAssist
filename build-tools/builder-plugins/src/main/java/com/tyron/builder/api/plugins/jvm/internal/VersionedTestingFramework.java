package com.tyron.builder.api.plugins.jvm.internal;

import com.google.common.base.Preconditions;

class VersionedTestingFramework {
    final Frameworks type;
    final String version;

    VersionedTestingFramework(Frameworks type, String version) {
        Preconditions.checkNotNull(version);
        this.type = type;
        this.version = version;
    }
}