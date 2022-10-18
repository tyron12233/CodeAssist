package org.gradle.launcher.daemon.configuration;

import java.io.File;
import java.util.List;

public interface DaemonServerConfiguration {

    File getBaseDir();

    int getIdleTimeout();

    int getPeriodicCheckIntervalMs();

    String getUid();

    List<String> getJvmOptions();

    DaemonParameters.Priority getPriority();

    boolean isSingleUse();
}
