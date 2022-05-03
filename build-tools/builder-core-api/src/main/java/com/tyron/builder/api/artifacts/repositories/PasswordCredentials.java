package com.tyron.builder.api.artifacts.repositories;

import javax.annotation.Nullable;

/**
 * A username/password credentials that can be used to login to password-protected remote repository.
 */
public interface PasswordCredentials extends com.tyron.builder.api.credentials.PasswordCredentials  {
    /**
     * Returns the user name to use when authenticating to this repository.
     *
     * @return The user name. May be null.
     */
    @Override
    @Nullable
    String getUsername();

    /**
     * Sets the user name to use when authenticating to this repository.
     *
     * @param userName The user name. May be null.
     */
    @Override
    void setUsername(@Nullable String userName);

    /**
     * Returns the password to use when authenticating to this repository.
     *
     * @return The password. May be null.
     */
    @Override
    @Nullable
    String getPassword();

    /**
     * Sets the password to use when authenticating to this repository.
     *
     * @param password The password. May be null.
     */
    @Override
    void setPassword(@Nullable String password);
}
