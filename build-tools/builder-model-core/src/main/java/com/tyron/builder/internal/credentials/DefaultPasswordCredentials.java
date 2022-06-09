package com.tyron.builder.internal.credentials;

import com.tyron.builder.api.artifacts.repositories.PasswordCredentials;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Internal;

public class DefaultPasswordCredentials implements PasswordCredentials {
    private String username;
    private String password;

    public DefaultPasswordCredentials() {
    }

    public DefaultPasswordCredentials(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Input
    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public void setUsername(String username) {
        this.username = username;
    }

    @Internal
    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        return String.format("Credentials [username: %s]", username);
    }
}
