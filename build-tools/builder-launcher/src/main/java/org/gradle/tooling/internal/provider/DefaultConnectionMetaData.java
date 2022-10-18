package org.gradle.tooling.internal.provider;

import org.gradle.tooling.internal.protocol.ConnectionMetaDataVersion1;
import org.gradle.util.GradleVersion;

class DefaultConnectionMetaData implements ConnectionMetaDataVersion1 {
    @Override
    public String getVersion() {
        return GradleVersion.current().getVersion();
    }

    @Override
    public String getDisplayName() {
        return "Gradle " + getVersion();
    }
}