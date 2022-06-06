package com.tyron.builder.internal.resource.transport.http;

import cz.msebera.android.httpclient.HttpEntityEnclosingRequest;
import cz.msebera.android.httpclient.HttpRequest;
import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.ProtocolException;
import cz.msebera.android.httpclient.client.methods.*;
import cz.msebera.android.httpclient.impl.client.DefaultRedirectStrategy;
import cz.msebera.android.httpclient.protocol.HttpContext;

import java.net.URI;

/**
 * A class which makes httpclient follow redirects for all http methods.
 * This has been introduced to overcome a regression caused by switching to apache httpclient as the transport mechanism for publishing (https://issues.gradle.org/browse/GRADLE-3312)
 * The rational for httpclient not following redirects, by default, can be found here: https://issues.apache.org/jira/browse/HTTPCLIENT-860
 */
public class AlwaysFollowAndPreserveMethodRedirectStrategy extends DefaultRedirectStrategy {

    public AlwaysFollowAndPreserveMethodRedirectStrategy() {
    }

    @Override
    protected boolean isRedirectable(String method) {
        return true;
    }

    @Override
    public HttpUriRequest getRedirect(HttpRequest request, HttpResponse response, HttpContext context) throws ProtocolException {
        URI uri = this.getLocationURI(request, response, context);
        String method = request.getRequestLine().getMethod();
        if (method.equalsIgnoreCase(HttpHead.METHOD_NAME)) {
            return new HttpHead(uri);
        } else if (method.equalsIgnoreCase(HttpPost.METHOD_NAME)) {
            return this.copyEntity(new HttpPost(uri), request);
        } else if (method.equalsIgnoreCase(HttpPut.METHOD_NAME)) {
            return this.copyEntity(new HttpPut(uri), request);
        } else if (method.equalsIgnoreCase(HttpDelete.METHOD_NAME)) {
            return new HttpDelete(uri);
        } else if (method.equalsIgnoreCase(HttpTrace.METHOD_NAME)) {
            return new HttpTrace(uri);
        } else if (method.equalsIgnoreCase(HttpOptions.METHOD_NAME)) {
            return new HttpOptions(uri);
        } else if (method.equalsIgnoreCase(HttpPatch.METHOD_NAME)) {
            return this.copyEntity(new HttpPatch(uri), request);
        } else {
            return new HttpGet(uri);
        }
    }

    private HttpUriRequest copyEntity(HttpEntityEnclosingRequestBase redirect, HttpRequest original) {
        if (original instanceof HttpEntityEnclosingRequest) {
            redirect.setEntity(((HttpEntityEnclosingRequest) original).getEntity());
        }
        return redirect;
    }
}
