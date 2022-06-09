package com.tyron.builder.internal.resource.transport.http;

import cz.msebera.android.httpclient.client.methods.HttpPut;
import cz.msebera.android.httpclient.entity.ContentType;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.ReadableContent;
import com.tyron.builder.internal.resource.transfer.ExternalResourceUploader;

import java.io.IOException;
import java.net.URI;

public class HttpResourceUploader implements ExternalResourceUploader {

    private final HttpClientHelper http;

    public HttpResourceUploader(HttpClientHelper http) {
        this.http = http;
    }

    @Override
    public void upload(ReadableContent resource, ExternalResourceName destination) throws IOException {
        HttpPut method = new HttpPut(destination.getUri());
        final RepeatableInputStreamEntity entity = new RepeatableInputStreamEntity(resource, ContentType.APPLICATION_OCTET_STREAM);
        method.setEntity(entity);
        try (HttpClientResponse response = http.performHttpRequest(method)) {
            if (!response.wasSuccessful()) {
                URI effectiveUri = response.getEffectiveUri();
                throw new HttpErrorStatusCodeException(response.getMethod(), effectiveUri.toString(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
            }
        }
    }
}
