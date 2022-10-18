package org.gradle.internal.resource.transport.http;

import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.authentication.http.BasicAuthentication;
import org.gradle.authentication.http.DigestAuthentication;
import org.gradle.authentication.http.HttpHeaderAuthentication;
import org.gradle.internal.authentication.AuthenticationSchemeRegistry;
import org.gradle.internal.authentication.DefaultBasicAuthentication;
import org.gradle.internal.authentication.DefaultDigestAuthentication;
import org.gradle.internal.authentication.DefaultHttpHeaderAuthentication;
import org.gradle.internal.resource.connector.ResourceConnectorFactory;
import org.gradle.internal.service.ServiceRegistration;
import org.gradle.internal.service.scopes.AbstractPluginServiceRegistry;

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
