package com.tyron.builder.internal.resource.transport.http;

import org.apache.http.impl.client.DefaultRedirectStrategy;

public class AllowFollowForMutatingMethodRedirectStrategy extends DefaultRedirectStrategy {

    public AllowFollowForMutatingMethodRedirectStrategy() {
    }

    @Override
    protected boolean isRedirectable(String method) {
        return true;
    }

}
