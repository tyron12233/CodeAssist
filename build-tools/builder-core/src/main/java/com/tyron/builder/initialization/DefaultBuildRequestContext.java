package com.tyron.builder.initialization;

import com.tyron.builder.api.initialization.BuildCancellationToken;

public class DefaultBuildRequestContext implements BuildRequestContext {

    public static BuildRequestContext of(
            BuildCancellationToken cancellationToken,
            BuildEventConsumer eventConsumer,
            BuildClientMetaData buildClientMetaData,
            long startTime
    ) {
        return new DefaultBuildRequestContext(cancellationToken, eventConsumer, buildClientMetaData, startTime);
    }

    private final BuildCancellationToken cancellationToken;
    private final BuildEventConsumer eventConsumer;
    private final BuildClientMetaData buildClientMetaData;
    private final long startTime;

    private DefaultBuildRequestContext(BuildCancellationToken cancellationToken,
                                       BuildEventConsumer eventConsumer,
                                       BuildClientMetaData buildClientMetaData,
                                       long startTime) {
        this.cancellationToken = cancellationToken;
        this.eventConsumer = eventConsumer;
        this.buildClientMetaData = buildClientMetaData;

        this.startTime = startTime;
    }
    @Override
    public BuildCancellationToken getCancellationToken() {
        return cancellationToken;
    }

    @Override
    public BuildEventConsumer getEventConsumer() {
        return eventConsumer;
    }

    @Override
    public BuildClientMetaData getClient() {
        return buildClientMetaData;
    }

    @Override
    public long getStartTime() {
        return startTime;
    }

    @Override
    public boolean isInteractive() {
        return false;
    }
}
