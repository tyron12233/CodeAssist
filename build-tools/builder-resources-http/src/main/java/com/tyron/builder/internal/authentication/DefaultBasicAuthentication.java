package com.tyron.builder.internal.authentication;

import com.tyron.builder.api.credentials.PasswordCredentials;
import com.tyron.builder.authentication.http.BasicAuthentication;

public class DefaultBasicAuthentication extends AbstractAuthentication implements BasicAuthentication {
    public DefaultBasicAuthentication(String name) {
        super(name, BasicAuthentication.class, PasswordCredentials.class);
    }

    @Override
    public boolean requiresCredentials() {
        return true;
    }
}
