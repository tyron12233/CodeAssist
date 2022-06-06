package com.tyron.builder.internal.resource.transport.http;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.authentication.Authentication;
import com.tyron.builder.authentication.http.BasicAuthentication;
import com.tyron.builder.authentication.http.DigestAuthentication;
import com.tyron.builder.authentication.http.HttpHeaderAuthentication;
import com.tyron.builder.internal.authentication.AllSchemesAuthentication;
import com.tyron.builder.internal.resource.connector.ResourceConnectorFactory;
import com.tyron.builder.internal.resource.connector.ResourceConnectorSpecification;
import com.tyron.builder.internal.resource.transfer.DefaultExternalResourceConnector;
import com.tyron.builder.internal.resource.transfer.ExternalResourceConnector;

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
