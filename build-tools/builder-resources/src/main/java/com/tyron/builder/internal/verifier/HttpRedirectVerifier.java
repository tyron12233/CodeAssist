package com.tyron.builder.internal.verifier;

import java.net.URI;
import java.util.Collection;

/**
 * Use {@link HttpRedirectVerifierFactory#create} to instantiate an instance of this.
 */
@FunctionalInterface
public interface HttpRedirectVerifier {
    /**
     * Perform verification on the URI's in an HTTP request's redirect chain.
     */
    void validateRedirects(Collection<URI> redirectLocations);
}
