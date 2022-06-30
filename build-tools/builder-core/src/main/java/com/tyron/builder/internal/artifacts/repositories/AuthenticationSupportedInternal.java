package com.tyron.builder.internal.artifacts.repositories;

import com.tyron.builder.api.artifacts.repositories.AuthenticationSupported;
import com.tyron.builder.api.credentials.Credentials;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.authentication.Authentication;

import java.util.Collection;

public interface AuthenticationSupportedInternal extends AuthenticationSupported {
    /**
     * Returns the configured authentication schemes or an instance of {@link com.tyron.builder.internal.authentication.AllSchemesAuthentication}
     * if none have been configured yet credentials have been configured.
     */
    Collection<Authentication> getConfiguredAuthentication();

    void setConfiguredCredentials(Credentials credentials);

    Property<Credentials> getConfiguredCredentials();
}
