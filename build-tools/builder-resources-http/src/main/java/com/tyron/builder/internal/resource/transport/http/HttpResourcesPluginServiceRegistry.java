package com.tyron.builder.internal.resource.transport.http;

import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.authentication.http.BasicAuthentication;
import com.tyron.builder.authentication.http.DigestAuthentication;
import com.tyron.builder.authentication.http.HttpHeaderAuthentication;
import com.tyron.builder.internal.authentication.AuthenticationSchemeRegistry;
import com.tyron.builder.internal.authentication.DefaultBasicAuthentication;
import com.tyron.builder.internal.authentication.DefaultDigestAuthentication;
import com.tyron.builder.internal.authentication.DefaultHttpHeaderAuthentication;
import com.tyron.builder.internal.resource.connector.ResourceConnectorFactory;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;

public class HttpResourcesPluginServiceRegistry extends AbstractPluginServiceRegistry {
    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new GlobalScopeServices());
    }

    @Override
    public void registerBuildServices(ServiceRegistration registration) {
        registration.addProvider(new AuthenticationSchemeAction());
    }

    private static class GlobalScopeServices {
        SslContextFactory createSslContextFactory() {
            return new DefaultSslContextFactory();
        }

        HttpClientHelper.Factory createHttpClientHelperFactory(DocumentationRegistry documentationRegistry) {
            return HttpClientHelper.Factory.createFactory(documentationRegistry);
        }

        ResourceConnectorFactory createHttpConnectorFactory(SslContextFactory sslContextFactory, HttpClientHelper.Factory httpClientHelperFactory) {
            return new HttpConnectorFactory(sslContextFactory, httpClientHelperFactory);
        }
    }

    private static class AuthenticationSchemeAction {
        public void configure(ServiceRegistration registration, AuthenticationSchemeRegistry authenticationSchemeRegistry) {
            authenticationSchemeRegistry.registerScheme(BasicAuthentication.class, DefaultBasicAuthentication.class);
            authenticationSchemeRegistry.registerScheme(DigestAuthentication.class, DefaultDigestAuthentication.class);
            authenticationSchemeRegistry.registerScheme(HttpHeaderAuthentication.class, DefaultHttpHeaderAuthentication.class);
        }
    }
}
