package org.gradle.internal.authentication;

import org.gradle.api.credentials.PasswordCredentials;
import org.gradle.authentication.http.BasicAuthentication;

public class DefaultBasicAuthentication extends AbstractAuthentication implements BasicAuthentication {
    public DefaultBasicAuthentication(String name) {
        super(name, BasicAuthentication.class, PasswordCredentials.class);
    }

    @Override
    public boolean requiresCredentials() {
        return true;
    }
}
