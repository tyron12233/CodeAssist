package com.tyron.builder.initialization;

public class DefaultBuildRequestContext implements BuildRequestContext {

    private final BuildCancellationToken token;
    private final BuildEventConsumer buildEventConsumer;
    private final BuildRequestMetaData metaData;

    // TODO: Decide if we want to push the gate concept into TAPI or other entry points
    // currently, a gate is only used by continuous build and can only be controlled from within the build.
    public DefaultBuildRequestContext(BuildRequestMetaData metaData, BuildCancellationToken token, BuildEventConsumer buildEventConsumer) {
        this.metaData = metaData;
        this.token = token;
        this.buildEventConsumer = buildEventConsumer;
    }

    @Override
    public BuildEventConsumer getEventConsumer() {
        return buildEventConsumer;
    }

    @Override
    public BuildCancellationToken getCancellationToken() {
        return token;
    }

    @Override
    public BuildClientMetaData getClient() {
        return metaData.getClient();
    }

    @Override
    public long getStartTime() {
        return metaData.getStartTime();
    }

    @Override
    public boolean isInteractive() {
        return metaData.isInteractive();
    }
}
