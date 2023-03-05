package org.gradle.internal.authentication;

import org.gradle.api.credentials.Credentials;
import org.gradle.authentication.Authentication;

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
