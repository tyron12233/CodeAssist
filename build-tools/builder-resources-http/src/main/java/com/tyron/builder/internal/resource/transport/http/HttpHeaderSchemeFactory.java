package com.tyron.builder.internal.resource.transport.http;

import cz.msebera.android.httpclient.annotation.Contract;
import cz.msebera.android.httpclient.annotation.ThreadingBehavior;
import cz.msebera.android.httpclient.auth.AuthScheme;
import cz.msebera.android.httpclient.protocol.HttpContext;

import java.nio.charset.Charset;

@Contract(threading = ThreadingBehavior.IMMUTABLE)
@SuppressWarnings("deprecation")
public class HttpHeaderSchemeFactory implements cz.msebera.android.httpclient.auth.AuthSchemeFactory, cz.msebera.android.httpclient.auth.AuthSchemeProvider {

    public HttpHeaderSchemeFactory(final Charset charset) {
        super();
    }

    public HttpHeaderSchemeFactory() {
        this(null);
    }

    @Override
    public AuthScheme newInstance(final cz.msebera.android.httpclient.params.HttpParams params) {
        return new HttpHeaderAuthScheme();
    }

    @Override
    public AuthScheme create(final HttpContext context) {
        return new HttpHeaderAuthScheme();
    }

}
