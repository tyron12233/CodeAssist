package org.gradle.internal.credentials;

import org.gradle.api.credentials.AwsCredentials;
import org.gradle.api.tasks.Internal;

public class DefaultAwsCredentials implements AwsCredentials {

    private String accessKey;
    private String secretKey;
    private String sessionToken;

    @Internal
    @Override
    public String getAccessKey() {
        return accessKey;
    }

    @Override
    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    @Internal
    @Override
    public String getSecretKey() {
        return secretKey;
    }

    @Override
    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    @Internal
    @Override
    public String getSessionToken() {
        return sessionToken;
    }

    @Override
    public void setSessionToken(String token) {
        this.sessionToken = token;
    }
}
