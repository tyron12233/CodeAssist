package com.tyron.builder.initialization;


import com.tyron.builder.internal.time.Time;
import com.tyron.builder.configuration.GradleLauncherMetaData;

public class DefaultBuildRequestMetaData implements BuildRequestMetaData {
    private final BuildClientMetaData clientMetaData;
    private final long startTime;
    private final boolean interactive;

    public DefaultBuildRequestMetaData(BuildClientMetaData clientMetaData, long startTime, boolean interactive) {
        this.clientMetaData = clientMetaData;
        this.startTime = startTime;
        this.interactive = interactive;
    }

    public DefaultBuildRequestMetaData(long startTime) {
        this(new GradleLauncherMetaData(), startTime, false);
    }

    public DefaultBuildRequestMetaData(long startTime, boolean interactive) {
        this(new GradleLauncherMetaData(), startTime, interactive);
    }

    public DefaultBuildRequestMetaData(BuildClientMetaData buildClientMetaData) {
        this(buildClientMetaData, Time.currentTimeMillis(), false);
    }

    @Override
    public BuildClientMetaData getClient() {
        return clientMetaData;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    @Override
    public boolean isInteractive() {
        return interactive;
    }
}