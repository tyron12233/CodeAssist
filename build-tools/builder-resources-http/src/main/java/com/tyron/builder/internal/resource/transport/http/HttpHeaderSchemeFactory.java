package com.tyron.builder.internal.resource.transport.http;

import org.apache.http.annotation.Contract;
import org.apache.http.annotation.ThreadingBehavior;
import org.apache.http.auth.AuthScheme;
import org.apache.http.protocol.HttpContext;

import java.nio.charset.Charset;

@Contract(threading = ThreadingBehavior.IMMUTABLE)
@SuppressWarnings("deprecation")
public class HttpHeaderSchemeFactory implements org.apache.http.auth.AuthSchemeFactory, org.apache.http.auth.AuthSchemeProvider {

    public HttpHeaderSchemeFactory(final Charset charset) {
        super();
    }

    public HttpHeaderSchemeFactory() {
        this(null);
    }

    @Override
    public AuthScheme newInstance(final org.apache.http.params.HttpParams params) {
        return new HttpHeaderAuthScheme();
    }

    @Override
    public AuthScheme create(final HttpContext context) {
        return new HttpHeaderAuthScheme();
    }

}
