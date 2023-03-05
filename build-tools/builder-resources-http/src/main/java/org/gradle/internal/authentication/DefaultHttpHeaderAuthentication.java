package org.gradle.internal.authentication;

import org.gradle.api.credentials.HttpHeaderCredentials;
import org.gradle.authentication.http.HttpHeaderAuthentication;

public class DefaultHttpHeaderAuthentication extends AbstractAuthentication implements HttpHeaderAuthentication {
    public DefaultHttpHeaderAuthentication(String name) {
        super(name, HttpHeaderAuthentication.class, HttpHeaderCredentials.class);
    }

    @Override
    public boolean requiresCredentials() {
        return true;
    }
}
