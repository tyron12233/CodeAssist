package org.gradle.internal.resource;

import org.gradle.internal.verifier.HttpRedirectVerifier;

import java.net.URI;

public interface TextUriResourceLoader {
    TextResource loadUri(String description, URI sourceUri);

    @FunctionalInterface
    interface Factory {
        TextUriResourceLoader create(HttpRedirectVerifier redirectVerifier);
    }
}
