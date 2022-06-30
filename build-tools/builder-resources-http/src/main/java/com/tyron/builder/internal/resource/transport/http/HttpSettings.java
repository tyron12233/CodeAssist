package com.tyron.builder.internal.resource.transport.http;

import com.tyron.builder.authentication.Authentication;
import com.tyron.builder.internal.verifier.HttpRedirectVerifier;

import javax.net.ssl.HostnameVerifier;
import java.util.Collection;

public interface HttpSettings {
    HttpProxySettings getProxySettings();

    HttpProxySettings getSecureProxySettings();

    HttpTimeoutSettings getTimeoutSettings();

    int getMaxRedirects();

    HttpRedirectVerifier getRedirectVerifier();

    RedirectMethodHandlingStrategy getRedirectMethodHandlingStrategy();

    Collection<Authentication> getAuthenticationSettings();

    SslContextFactory getSslContextFactory();

    HostnameVerifier getHostnameVerifier();

    enum RedirectMethodHandlingStrategy {

        /**
         * Follows 307/308 redirects with original method.
         *
         * Mutating requests redirected with 301/302/303 are followed with a GET request.
         */
        ALLOW_FOLLOW_FOR_MUTATIONS,

        /**
         * Always redirects with the original method regardless of type of redirect.
         *
         * @see AlwaysFollowAndPreserveMethodRedirectStrategy for discussion of why this exists (and is default)
         */
        ALWAYS_FOLLOW_AND_PRESERVE
    }
}
