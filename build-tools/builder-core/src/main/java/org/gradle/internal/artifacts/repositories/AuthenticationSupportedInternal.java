package org.gradle.internal.artifacts.repositories;

import org.gradle.api.artifacts.repositories.AuthenticationSupported;
import org.gradle.api.credentials.Credentials;
import org.gradle.api.provider.Property;
import org.gradle.authentication.Authentication;

import java.util.Collection;

public interface AuthenticationSupportedInternal extends AuthenticationSupported {
    /**
     * Returns the configured authentication schemes or an instance of {@link org.gradle.internal.authentication.AllSchemesAuthentication}
     * if none have been configured yet credentials have been configured.
     */
    Collection<Authentication> getConfiguredAuthentication();

    void setConfiguredCredentials(Credentials credentials);

    Property<Credentials> getConfiguredCredentials();
}
