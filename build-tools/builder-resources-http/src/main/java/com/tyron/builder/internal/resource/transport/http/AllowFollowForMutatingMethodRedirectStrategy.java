package com.tyron.builder.internal.resource.transport.http;

import cz.msebera.android.httpclient.impl.client.DefaultRedirectStrategy;

public class AllowFollowForMutatingMethodRedirectStrategy extends DefaultRedirectStrategy {

    public AllowFollowForMutatingMethodRedirectStrategy() {
    }

    @Override
    protected boolean isRedirectable(String method) {
        return true;
    }

}
