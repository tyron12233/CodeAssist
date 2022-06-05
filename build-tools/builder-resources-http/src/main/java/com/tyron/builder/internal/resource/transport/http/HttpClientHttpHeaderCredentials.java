package com.tyron.builder.internal.resource.transport.http;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.auth.Credentials;
import cz.msebera.android.httpclient.message.BasicHeader;

import java.security.Principal;

public class HttpClientHttpHeaderCredentials implements Credentials {

    private final Header header;

    public HttpClientHttpHeaderCredentials(String name, String value) {
        this.header = new BasicHeader(name, value);
    }

    public Header getHeader() {
        return header;
    }

    @Override
    public Principal getUserPrincipal() {
        return null;
    }

    @Override
    public String getPassword() {
        return null;
    }
}
