package org.gradle.tooling.internal.provider.connection;

import javax.annotation.Nullable;
import java.io.File;

public interface ProviderConnectionParameters {
    boolean getVerboseLogging();

    String getConsumerVersion();

    @Nullable
    File getGradleUserHomeDir(File defaultValue);
}
