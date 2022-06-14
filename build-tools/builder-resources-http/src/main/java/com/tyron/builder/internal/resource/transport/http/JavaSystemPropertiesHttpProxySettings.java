package com.tyron.builder.internal.resource.transport.http;

public class JavaSystemPropertiesHttpProxySettings extends JavaSystemPropertiesProxySettings {
    private static final int DEFAULT_PROXY_PORT = 80;
    private static final String PROPERTY_PREFIX = "http";

    public JavaSystemPropertiesHttpProxySettings() {
        super(PROPERTY_PREFIX, DEFAULT_PROXY_PORT);
    }
}
