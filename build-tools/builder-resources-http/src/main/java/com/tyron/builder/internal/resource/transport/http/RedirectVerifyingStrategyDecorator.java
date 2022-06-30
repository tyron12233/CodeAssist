package com.tyron.builder.internal.resource.transport.http;

import cz.msebera.android.httpclient.HttpRequest;
import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.ProtocolException;
import cz.msebera.android.httpclient.client.RedirectStrategy;
import cz.msebera.android.httpclient.client.methods.HttpUriRequest;
import cz.msebera.android.httpclient.protocol.HttpContext;
import com.tyron.builder.internal.verifier.HttpRedirectVerifier;

import java.util.Collections;

final class RedirectVerifyingStrategyDecorator implements RedirectStrategy {

    private final RedirectStrategy delegate;
    private final HttpRedirectVerifier verifier;

    public RedirectVerifyingStrategyDecorator(RedirectStrategy delegate, HttpRedirectVerifier verifier) {
        this.delegate = delegate;
        this.verifier = verifier;
    }

    @Override
    public boolean isRedirected(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
        return delegate.isRedirected(request, response, context);
    }

    @Override
    public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
        HttpUriRequest redirectRequest = delegate.getRedirect(request, response, context);
        verifier.validateRedirects(Collections.singletonList(redirectRequest.getURI()));
        return redirectRequest;
    }
}
