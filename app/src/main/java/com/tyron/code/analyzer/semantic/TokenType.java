package com.tyron.code.analyzer.semantic;

import androidx.annotation.NonNull;

public class TokenType {

    public static final TokenType UNKNOWN = create("token.error-token");

    public static TokenType create(String scope, String... fallbackScopes) {
        return new TokenType(scope, fallbackScopes);
    }

    private final String scope;
    private final String[] fallbackScopes;

    public TokenType(@NonNull String scope, String[] fallbackScopes) {
        this.scope = scope;
        this.fallbackScopes = fallbackScopes;
    }

    public String getScope() {
        return scope;
    }

    public String[] getFallbackScopes() {
        return fallbackScopes;
    }

    @NonNull
    @Override
    public String toString() {
        return scope;
    }

}