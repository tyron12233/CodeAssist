package com.tyron.builder.internal.resource.transport.http;

import com.tyron.builder.internal.IoActions;
import com.tyron.builder.internal.resource.ExternalResourceName;
import com.tyron.builder.internal.resource.metadata.ExternalResourceMetaData;
import com.tyron.builder.internal.resource.transfer.AbstractExternalResourceAccessor;
import com.tyron.builder.internal.resource.transfer.ExternalResourceAccessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.URI;

public class HttpResourceAccessor extends AbstractExternalResourceAccessor implements ExternalResourceAccessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpResourceAccessor.class);
    private final HttpClientHelper http;

    public HttpResourceAccessor(HttpClientHelper http) {
        this.http = http;
    }

    @Override
    @Nullable
    public HttpResponseResource openResource(final ExternalResourceName location, boolean revalidate) {
        String uri = location.getUri().toString();
        LOGGER.debug("Constructing external resource: {}", location);

        HttpClientResponse response = http.performGet(uri, revalidate);
        if (response != null) {
            return wrapResponse(location.getUri(), response);
        }

        return null;
    }

    /**
     * Same as #getResource except that it always gives access to the response body,
     * irrespective of the returned HTTP status code. Never returns {@code null}.
     */
    public HttpResponseResource getRawResource(final URI uri, boolean revalidate) {
        String location = uri.toString();
        LOGGER.debug("Constructing external resource: {}", location);
        HttpClientResponse response = http.performRawGet(location, revalidate);
        return wrapResponse(uri, response);
    }

    @Override
    public ExternalResourceMetaData getMetaData(ExternalResourceName location, boolean revalidate) {
        String uri = location.getUri().toString();
        LOGGER.debug("Constructing external resource metadata: {}", location);
        HttpClientResponse response = http.performHead(uri, revalidate);

        ExternalResourceMetaData result = null;
        if (response != null) {
            HttpResponseResource resource = new HttpResponseResource("HEAD", location.getUri(), response);
            try {
                result = resource.getMetaData();
            } finally {
                IoActions.closeQuietly(resource);
            }
        }
        return result;
    }

    private HttpResponseResource wrapResponse(URI uri, HttpClientResponse response) {
        return new HttpResponseResource("GET", uri, response);
    }

}
