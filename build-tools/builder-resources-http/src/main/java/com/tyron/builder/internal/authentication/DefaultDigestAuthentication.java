package com.tyron.builder.internal.authentication;

import com.tyron.builder.api.credentials.PasswordCredentials;
import com.tyron.builder.authentication.http.DigestAuthentication;

public class DefaultDigestAuthentication extends AbstractAuthentication implements DigestAuthentication {
    public DefaultDigestAuthentication(String name) {
        super(name, DigestAuthentication.class, PasswordCredentials.class);
    }

    @Override
    public boolean requiresCredentials() {
        return true;
    }
}
