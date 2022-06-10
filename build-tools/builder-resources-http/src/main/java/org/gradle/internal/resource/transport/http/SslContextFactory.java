package org.gradle.internal.resource.transport.http;

import javax.net.ssl.SSLContext;

public interface SslContextFactory {
    SSLContext createSslContext();
}
