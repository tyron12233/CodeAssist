package com.tyron.builder.api.credentials;

import javax.annotation.Nullable;

/**
 * A username/password credentials that can be used to login to something protected by a username and password.
 *
 * @since 3.5
 */
public interface PasswordCredentials extends Credentials {
    /**
     * Returns the user name to use when authenticating.
     *
     * @return The user name. May be null.
     */
    @Nullable
    String getUsername();

    /**
     * Sets the user name to use when authenticating.
     *
     * @param userName The user name. May be null.
     */
    void setUsername(@Nullable String userName);

    /**
     * Returns the password to use when authenticating.
     *
     * @return The password. May be null.
     */
    @Nullable
    String getPassword();

    /**
     * Sets the password to use when authenticating.
     *
     * @param password The password. May be null.
     */
    void setPassword(@Nullable String password);
}
