package org.gradle.internal.authentication;

import org.gradle.api.NonExtensible;
import org.gradle.api.credentials.Credentials;
import org.gradle.authentication.Authentication;

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
