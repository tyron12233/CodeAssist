package org.gradle.internal.authentication;

import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.authentication.http.DigestAuthentication;

public class DefaultDigestAuthentication extends AbstractAuthentication implements DigestAuthentication {
    public DefaultDigestAuthentication(String name) {
        super(name, DigestAuthentication.class, PasswordCredentials.class);
    }

    @Override
    public boolean requiresCredentials() {
        return true;
    }
}
