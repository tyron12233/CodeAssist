package com.tyron.builder.internal.resource.transport.http;

public class JavaSystemPropertiesSecureHttpProxySettings extends JavaSystemPropertiesProxySettings {
    private static final int DEFAULT_PROXY_PORT = 443;
    private static final String PROPERTY_PREFIX = "https";

    public JavaSystemPropertiesSecureHttpProxySettings() {
        super(PROPERTY_PREFIX, DEFAULT_PROXY_PORT);
    }
}
