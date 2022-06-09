package com.tyron.builder.internal.resource.transport.http;

public interface HttpTimeoutSettings {

    int getConnectionTimeoutMs();

    int getSocketTimeoutMs();
}
