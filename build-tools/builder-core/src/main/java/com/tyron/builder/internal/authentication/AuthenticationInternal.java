package com.tyron.builder.internal.authentication;

import com.tyron.builder.api.NonExtensible;
import com.tyron.builder.api.credentials.Credentials;
import com.tyron.builder.authentication.Authentication;

import java.util.Collection;

@NonExtensible
public interface AuthenticationInternal extends Authentication {
    boolean supports(Credentials credentials);

    Credentials getCredentials();

    void setCredentials(Credentials credentials);

    Class<? extends Authentication> getType();

    boolean requiresCredentials();

    void addHost(String host, int port);

    Collection<HostAndPort> getHostsForAuthentication();

    interface HostAndPort {

        /**
         * The hostname that the credentials are required for.
         *
         * null means "any host"
         */
        String getHost();

        /**
         * The port that the credentials are required for
         *
         * -1 means "any port"
         */
        int getPort();
    }
}
