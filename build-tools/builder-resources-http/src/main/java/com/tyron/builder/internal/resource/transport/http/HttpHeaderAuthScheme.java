package com.tyron.builder.internal.resource.transport.http;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpRequest;
import cz.msebera.android.httpclient.auth.AuthenticationException;
import cz.msebera.android.httpclient.auth.ContextAwareAuthScheme;
import cz.msebera.android.httpclient.auth.Credentials;
import cz.msebera.android.httpclient.auth.MalformedChallengeException;
import cz.msebera.android.httpclient.protocol.BasicHttpContext;
import cz.msebera.android.httpclient.protocol.HttpContext;
import cz.msebera.android.httpclient.util.Args;

public class HttpHeaderAuthScheme implements ContextAwareAuthScheme {

    public static final String AUTH_SCHEME_NAME = "header";

    @Override
    public void processChallenge(final Header header) throws MalformedChallengeException {
    }

    @Override
    public String getSchemeName() {
        return AUTH_SCHEME_NAME;
    }

    @Override
    public String getParameter(final String name) {
        return null;
    }

    @Override
    public String getRealm() {
        return null;
    }

    @Override
    public boolean isConnectionBased() {
        return false;
    }

    @Override
    public boolean isComplete() {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public Header authenticate(final Credentials credentials, final HttpRequest request) throws AuthenticationException {
        return this.authenticate(credentials, request, new BasicHttpContext());
    }

    @Override
    public Header authenticate(final Credentials credentials, final HttpRequest request, final HttpContext context) throws AuthenticationException {
        Args.check(credentials instanceof HttpClientHttpHeaderCredentials, "Only " + HttpClientHttpHeaderCredentials.class.getCanonicalName() + " supported for AuthScheme " + this.getClass().getCanonicalName() + ", got " + credentials.getClass().getName());
        HttpClientHttpHeaderCredentials httpClientHttpHeaderCredentials = (HttpClientHttpHeaderCredentials) credentials;
        return httpClientHttpHeaderCredentials.getHeader();
    }
}
