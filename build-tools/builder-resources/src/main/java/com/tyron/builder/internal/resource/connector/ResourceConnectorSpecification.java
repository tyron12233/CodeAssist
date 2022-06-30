package com.tyron.builder.internal.resource.connector;

import com.tyron.builder.authentication.Authentication;
import com.tyron.builder.internal.verifier.HttpRedirectVerifier;

import java.util.Collection;
import java.util.Collections;

public interface ResourceConnectorSpecification {
    default <T> T getCredentials(Class<T> type) {
        return null;
    }

    default Collection<Authentication> getAuthentications() {
        return Collections.emptyList();
    }

    default HttpRedirectVerifier getRedirectVerifier() {
        return uris -> {
        };
    }
}
