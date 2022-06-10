package org.gradle.internal.resource.transport.http;

public interface HttpTimeoutSettings {

    int getConnectionTimeoutMs();

    int getSocketTimeoutMs();
}
