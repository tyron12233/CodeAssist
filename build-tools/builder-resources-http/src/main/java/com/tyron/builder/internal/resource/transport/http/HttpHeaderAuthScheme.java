package com.tyron.builder.internal.resource.transport.http;

import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.auth.AuthenticationException;
import org.apache.http.auth.ContextAwareAuthScheme;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.MalformedChallengeException;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.Args;

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
