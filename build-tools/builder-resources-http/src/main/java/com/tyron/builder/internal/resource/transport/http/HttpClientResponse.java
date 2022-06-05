package com.tyron.builder.internal.resource.transport.http;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpEntity;
import cz.msebera.android.httpclient.StatusLine;
import cz.msebera.android.httpclient.client.methods.CloseableHttpResponse;
import cz.msebera.android.httpclient.client.utils.HttpClientUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

public class HttpClientResponse implements Closeable {

    private final String method;
    private final URI effectiveUri;
    private final CloseableHttpResponse httpResponse;
    private boolean closed;

    HttpClientResponse(String method, URI effectiveUri, CloseableHttpResponse httpResponse) {
        this.method = method;
        this.effectiveUri = effectiveUri;
        this.httpResponse = httpResponse;
    }

    public String getHeader(String name) {
        Header header = httpResponse.getFirstHeader(name);
        return header == null ? null : header.getValue();
    }

    public InputStream getContent() throws IOException {
        HttpEntity entity = httpResponse.getEntity();
        if (entity == null) {
            throw new IOException(String.format("Response %d: %s has no content!", getStatusLine().getStatusCode(), getStatusLine().getReasonPhrase()));
        }
        return entity.getContent();
    }

    public StatusLine getStatusLine() {
        return httpResponse.getStatusLine();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            HttpClientUtils.closeQuietly(httpResponse);
        }
    }

    String getMethod() {
        return method;
    }

    URI getEffectiveUri() {
        return effectiveUri;
    }

    boolean wasSuccessful() {
        int statusCode = getStatusLine().getStatusCode();
        return statusCode >= 200 && statusCode < 400;
    }

    boolean wasMissing() {
        int statusCode = getStatusLine().getStatusCode();
        return statusCode == 404;
    }
}
