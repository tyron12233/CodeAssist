package com.tyron.builder.internal.resource.transport.http;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;
import cz.msebera.android.httpclient.HttpHeaders;
import cz.msebera.android.httpclient.client.methods.CloseableHttpResponse;
import cz.msebera.android.httpclient.client.methods.HttpGet;
import cz.msebera.android.httpclient.client.methods.HttpHead;
import cz.msebera.android.httpclient.client.methods.HttpRequestBase;
import cz.msebera.android.httpclient.client.utils.URIBuilder;
import cz.msebera.android.httpclient.impl.client.CloseableHttpClient;
import cz.msebera.android.httpclient.impl.client.HttpClientBuilder;
import cz.msebera.android.httpclient.protocol.BasicHttpContext;
import cz.msebera.android.httpclient.protocol.HttpContext;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.internal.UncheckedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLHandshakeException;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static cz.msebera.android.httpclient.client.protocol.HttpClientContext.REDIRECT_LOCATIONS;

/**
 * Provides some convenience and unified logging.
 */
public class HttpClientHelper implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpClientHelper.class);
    private CloseableHttpClient client;
    private final DocumentationRegistry documentationRegistry;
    private final HttpSettings settings;

    /**
     * Maintains a queue of contexts which are shared between threads when authentication
     * is activated. When a request is performed, it will pick a context from the queue,
     * and create a new one whenever it's not available (which either means it's the first request
     * or that other requests are being performed concurrently). The queue will grow as big as
     * the max number of concurrent requests executed.
     */
    private final ConcurrentLinkedQueue<HttpContext> sharedContext;

    /**
     * Use {@link HttpClientHelper.Factory#create(HttpSettings)} to instantiate instances.
     */
    @VisibleForTesting
    HttpClientHelper(DocumentationRegistry documentationRegistry, HttpSettings settings) {
        this.documentationRegistry = documentationRegistry;
        this.settings = settings;
        if (!settings.getAuthenticationSettings().isEmpty()) {
            sharedContext = new ConcurrentLinkedQueue<HttpContext>();
        } else {
            sharedContext = null;
        }
    }

    private HttpClientResponse performRawHead(String source, boolean revalidate) {
        return performRequest(new HttpHead(source), revalidate);
    }

    public HttpClientResponse performHead(String source, boolean revalidate) {
        return processResponse(performRawHead(source, revalidate));
    }

    HttpClientResponse performRawGet(String source, boolean revalidate) {
        return performRequest(new HttpGet(source), revalidate);
    }

    public HttpClientResponse performGet(String source, boolean revalidate) {
        return processResponse(performRawGet(source, revalidate));
    }

    public HttpClientResponse performRequest(HttpRequestBase request, boolean revalidate) {
        String method = request.getMethod();
        if (revalidate) {
            request.addHeader(HttpHeaders.CACHE_CONTROL, "max-age=0");
        }
        try {
            return executeGetOrHead(request);
        } catch (FailureFromRedirectLocation e) {
            throw new HttpRequestException(String.format("Could not %s '%s'.", method, stripUserCredentials(e.getLastRedirectLocation())), e.getCause());
        } catch (IOException e) {
            Exception cause = e;
            if (e instanceof SSLHandshakeException) {
                SSLHandshakeException sslException = (SSLHandshakeException) e;
                final String confidence;
                if (sslException.getMessage() != null && sslException.getMessage().contains("protocol_version")) {
                    // If we're handling an SSLHandshakeException with the error of 'protocol_version' we know that the server doesn't support this protocol.
                    confidence = "The server does not";
                } else {
                    // Sometimes the SSLHandshakeException doesn't include the 'protocol_version', even though this is the cause of the error.
                    // Tell the user this but with less confidence.
                    confidence = "The server may not";
                }
                String message = String.format(
                    confidence + " support the client's requested TLS protocol versions: (%s). " +
                        "You may need to configure the client to allow other protocols to be used. " +
                        "See: %s",
                    String.join(", ", HttpClientConfigurer.supportedTlsVersions()),
                    documentationRegistry.getDocumentationFor("build_environment", "gradle_system_properties")
                );
                cause = new HttpRequestException(message, cause);
            }
            throw new HttpRequestException(String.format("Could not %s '%s'.", method, stripUserCredentials(request.getURI())), cause);
        }
    }

    protected HttpClientResponse executeGetOrHead(HttpRequestBase method) throws IOException {
        HttpClientResponse response = performHttpRequest(method);
        // Consume content for non-successful, responses. This avoids the connection being left open.
        if (!response.wasSuccessful()) {
            response.close();
        }
        return response;
    }

    public HttpClientResponse performHttpRequest(HttpRequestBase request) throws IOException {
        if (sharedContext == null) {
            // There's no authentication involved, requests can be done concurrently
            return performHttpRequest(request, new BasicHttpContext());
        }
        HttpContext httpContext = nextAvailableSharedContext();
        try {
            return performHttpRequest(request, httpContext);
        } finally {
            sharedContext.add(httpContext);
        }
    }

    private HttpContext nextAvailableSharedContext() {
        HttpContext context = sharedContext.poll();
        if (context == null) {
            return new BasicHttpContext();
        }
        return context;
    }

    private HttpClientResponse performHttpRequest(HttpRequestBase request, HttpContext httpContext) throws IOException {
        // Without this, HTTP Client prohibits multiple redirects to the same location within the same context
        httpContext.removeAttribute(REDIRECT_LOCATIONS);
        LOGGER.debug("Performing HTTP {}: {}", request.getMethod(), stripUserCredentials(request.getURI()));

        try {
            CloseableHttpResponse response = getClient().execute(request, httpContext);
            return toHttpClientResponse(request, httpContext, response);
        } catch (IOException e) {
            validateRedirectChain(httpContext);
            URI lastRedirectLocation = stripUserCredentials(getLastRedirectLocation(httpContext));
            throw (lastRedirectLocation == null) ? e : new FailureFromRedirectLocation(lastRedirectLocation, e);
        }
    }

    private HttpClientResponse toHttpClientResponse(HttpRequestBase request, HttpContext httpContext, CloseableHttpResponse response) {
        validateRedirectChain(httpContext);
        URI lastRedirectLocation = getLastRedirectLocation(httpContext);
        URI effectiveUri = lastRedirectLocation == null ? request.getURI() : lastRedirectLocation;
        return new HttpClientResponse(request.getMethod(), effectiveUri, response);
    }

    /**
     * Validates that no redirect used an insecure protocol.
     * Redirecting through an insecure protocol can allow for a MITM redirect to an attacker controlled HTTPS server.
     */
    private void validateRedirectChain(HttpContext httpContext) {
        settings.getRedirectVerifier().validateRedirects(getRedirectLocations(httpContext));
    }

    @Nonnull
    private static List<URI> getRedirectLocations(HttpContext httpContext) {
        @SuppressWarnings("unchecked")
        List<URI> redirects = (List<URI>) httpContext.getAttribute(REDIRECT_LOCATIONS);
        return redirects == null ? Collections.emptyList() : redirects;
    }


    private static URI getLastRedirectLocation(HttpContext httpContext) {
        List<URI> redirectLocations = getRedirectLocations(httpContext);
        return redirectLocations.isEmpty() ? null : Iterables.getLast(redirectLocations);
    }

    private HttpClientResponse processResponse(HttpClientResponse response) {
        if (response.wasMissing()) {
            LOGGER.info("Resource missing. [HTTP {}: {}]", response.getMethod(), stripUserCredentials(response.getEffectiveUri()));
            return null;
        }
        if (!response.wasSuccessful()) {
            URI effectiveUri = stripUserCredentials(response.getEffectiveUri());
            LOGGER.info("Failed to get resource: {}. [HTTP {}: {})]", response.getMethod(), response.getStatusLine(), effectiveUri);
            throw new HttpErrorStatusCodeException(response.getMethod(), effectiveUri.toString(), response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase());
        }

        return response;
    }

    private synchronized CloseableHttpClient getClient() {
        if (client == null) {
            HttpClientBuilder builder = HttpClientBuilder.create();
            new HttpClientConfigurer(settings).configure(builder);
            this.client = builder.build();
        }
        return client;
    }

    @Override
    public synchronized void close() throws IOException {
        if (client != null) {
            client.close();
            if (sharedContext != null) {
                sharedContext.clear();
            }
        }
    }

    /**
     * Strips the {@link URI#getUserInfo() user info} from the {@link URI} making it
     * safe to appear in log messages.
     */
    @Nullable
    @VisibleForTesting
    static URI stripUserCredentials(@CheckForNull URI uri) {
        if (uri == null) {
            return null;
        }
        try {
            return new URIBuilder(uri).setUserInfo(null).build();
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e, true);
        }
    }

    private static class FailureFromRedirectLocation extends IOException {
        private final URI lastRedirectLocation;

        private FailureFromRedirectLocation(URI lastRedirectLocation, Throwable cause) {
            super(cause);
            this.lastRedirectLocation = lastRedirectLocation;
        }

        private URI getLastRedirectLocation() {
            return lastRedirectLocation;
        }
    }

    /**
     * Factory for creating the {@link HttpClientHelper}
     */
    @FunctionalInterface
    public interface Factory {
        HttpClientHelper create(HttpSettings settings);

        /**
         * Method should only be used for DI registry and testing.
         * For other uses of {@link HttpClientHelper}, inject an instance of {@link Factory} to create one.
         */
        static Factory createFactory(DocumentationRegistry documentationRegistry) {
            return settings -> new HttpClientHelper(documentationRegistry, settings);
        }
    }

}
