package org.gradle.internal.resource.connector;

import org.gradle.authentication.Authentication;
import org.gradle.internal.verifier.HttpRedirectVerifier;

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
