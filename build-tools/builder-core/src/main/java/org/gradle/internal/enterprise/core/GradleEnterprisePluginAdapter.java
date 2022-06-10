package org.gradle.internal.enterprise.core;

import javax.annotation.Nullable;

public interface GradleEnterprisePluginAdapter {

    boolean shouldSaveToConfigurationCache();

    void onLoadFromConfigurationCache();

    void buildFinished(@Nullable Throwable buildFailure);

}
