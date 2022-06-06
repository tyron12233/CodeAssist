package com.tyron.builder.internal.authentication;

import com.tyron.builder.api.credentials.Credentials;
import com.tyron.builder.authentication.Authentication;

/**
 * Authentication scheme representing all supported schemes for a given protocol
 */
public class AllSchemesAuthentication extends AbstractAuthentication {
    public AllSchemesAuthentication(Credentials credentials) {
        super("all", Authentication.class);
        this.setCredentials(credentials);
    }

    @Override
    public boolean supports(Credentials credentials) {
        return true;
    }

    @Override
    public boolean requiresCredentials() {
        return true;
    }
}
