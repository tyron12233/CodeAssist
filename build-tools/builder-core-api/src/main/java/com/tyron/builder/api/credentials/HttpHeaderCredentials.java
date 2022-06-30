package com.tyron.builder.api.credentials;

import javax.annotation.Nullable;

/**
 * Credentials that can be used to login to a protected server, e.g. a remote repository by using HTTP header.
 *
 * The properties used for creating credentials from a property are {@code repoAuthHeaderName} and {@code repoAuthHeaderValue}, where {@code repo} is the identity of the repository.
 *
 * @since 4.10
 */
public interface HttpHeaderCredentials extends Credentials {

    /**
     * Returns the header name to use when authenticating.
     *
     * @return The header name. May be null.
     */
    @Nullable
    String getName();

    /**
     * Sets the header name to use when authenticating.
     *
     * @param name The header name. May be null.
     */
    void setName(@Nullable String name);

    /**
     * Returns the header value to use when authenticating.
     *
     * @return The header value. May be null.
     */
    @Nullable
    String getValue();

    /**
     * Sets the header value to use when authenticating.
     *
     * @param value The header value. May be null.
     */
    void setValue(@Nullable String value);

}
