package org.gradle.internal.resource.transport.http;

import com.google.common.collect.ImmutableSet;
import org.gradle.authentication.Authentication;
import org.gradle.authentication.http.BasicAuthentication;
import org.gradle.authentication.http.DigestAuthentication;
import org.gradle.authentication.http.HttpHeaderAuthentication;
import org.gradle.internal.authentication.AllSchemesAuthentication;
import org.gradle.internal.resource.connector.ResourceConnectorFactory;
import org.gradle.internal.resource.connector.ResourceConnectorSpecification;
import org.gradle.internal.resource.transfer.DefaultExternalResourceConnector;
import org.gradle.internal.resource.transfer.ExternalResourceConnector;

import java.util.Set;

public class HttpConnectorFactory implements ResourceConnectorFactory {
    private final static Set<String> SUPPORTED_PROTOCOLS = ImmutableSet.of("http", "https");
    private final static Set<Class<? extends Authentication>> SUPPORTED_AUTHENTICATION = ImmutableSet.of(
        BasicAuthentication.class,
        DigestAuthentication.class,
        HttpHeaderAuthentication.class,
        AllSchemesAuthentication.class
    );

    private final SslContextFactory sslContextFactory;
    private final HttpClientHelper.Factory httpClientHelperFactory;

    public HttpConnectorFactory(SslContextFactory sslContextFactory, HttpClientHelper.Factory httpClientHelperFactory) {
        this.sslContextFactory = sslContextFactory;
        this.httpClientHelperFactory = httpClientHelperFactory;
    }

    @Override
    public Set<String> getSupportedProtocols() {
        return SUPPORTED_PROTOCOLS;
    }

    @Override
    public Set<Class<? extends Authentication>> getSupportedAuthentication() {
        return SUPPORTED_AUTHENTICATION;
    }

    @Override
    public ExternalResourceConnector createResourceConnector(ResourceConnectorSpecification connectionDetails) {
        HttpClientHelper http = httpClientHelperFactory.create(DefaultHttpSettings.builder()
            .withAuthenticationSettings(connectionDetails.getAuthentications())
            .withSslContextFactory(sslContextFactory)
            .withRedirectVerifier(connectionDetails.getRedirectVerifier())
            .build()
        );
        HttpResourceAccessor accessor = new HttpResourceAccessor(http);
        HttpResourceLister lister = new HttpResourceLister(accessor);
        HttpResourceUploader uploader = new HttpResourceUploader(http);
        return new DefaultExternalResourceConnector(accessor, lister, uploader);
    }
}
