package com.tyron.builder.internal.authentication;

import com.tyron.builder.api.credentials.HttpHeaderCredentials;
import com.tyron.builder.authentication.http.HttpHeaderAuthentication;

public class DefaultHttpHeaderAuthentication extends AbstractAuthentication implements HttpHeaderAuthentication {
    public DefaultHttpHeaderAuthentication(String name) {
        super(name, HttpHeaderAuthentication.class, HttpHeaderCredentials.class);
    }

    @Override
    public boolean requiresCredentials() {
        return true;
    }
}
