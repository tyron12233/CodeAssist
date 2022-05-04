package com.tyron.builder.api.credentials;

import javax.annotation.Nullable;

/**
 * Represents credentials used to authenticate with Amazon Web Services.
 */
public interface AwsCredentials extends Credentials {

    /**
     * Returns the access key to use to authenticate with AWS.
     */
    @Nullable
    String getAccessKey();

    /**
     * Sets the access key to use to authenticate with AWS.
     */
    void setAccessKey(@Nullable String accessKey);

    /**
     * Returns the secret key to use to authenticate with AWS.
     */
    @Nullable
    String getSecretKey();

    /**
     * Sets the secret key to use to authenticate with AWS.
     */
    void setSecretKey(@Nullable String secretKey);

    /**
     * Returns the secret key to use to authenticate with AWS.
     *
     * @since 3.3
     */
    @Nullable
    String getSessionToken();

    /**
     * Sets the secret key to use to authenticate with AWS.
     *
     * @since 3.3
     */
    void setSessionToken(@Nullable String token);

}
