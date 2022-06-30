package com.tyron.builder.internal.verifier;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Collection;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

import com.tyron.builder.util.GUtil;

/**
 * Used to create instances of {@link HttpRedirectVerifier}.
 */
public class HttpRedirectVerifierFactory {

    /**
     * Verifies that the base URL and all subsequent redirects followed during an interaction with a server are done so securely unless
     * the user has explicitly opted out from this protection.
     *
     * @param baseHost The host specified by the user.
     * @param allowInsecureProtocol If true, allows HTTP based connections.
     * @param insecureBaseHost Callback when the base host URL is insecure.
     * @param insecureRedirect Callback when the server returns an 30x redirect to an insecure server.
     */
    public static HttpRedirectVerifier create(
        @Nullable URI baseHost,
        boolean allowInsecureProtocol,
        Runnable insecureBaseHost,
        Consumer<URI> insecureRedirect
    ) {
        requireNonNull(insecureBaseHost, "insecureBaseHost must not be null");
        requireNonNull(insecureRedirect, "insecureRedirect must not be null");
        if (allowInsecureProtocol) {
            return NoopHttpRedirectVerifier.instance;
        } else {
            // Verify that the base URL is secure now.
            if (baseHost != null && !GUtil.isSecureUrl(baseHost)) {
                insecureBaseHost.run();
            }

            // Verify that any future redirect locations are secure.
            // Lambda will be called back on for every redirect in the chain.
            return redirectLocations ->
                redirectLocations
                    .stream()
                    .filter(url -> !GUtil.isSecureUrl(url))
                    .forEach(insecureRedirect);
        }
    }

    private static class NoopHttpRedirectVerifier implements HttpRedirectVerifier {
        private static NoopHttpRedirectVerifier instance = new NoopHttpRedirectVerifier();

        @Override
        public void validateRedirects(Collection<URI> redirectLocations) {
            // Noop
        }
    }
}
