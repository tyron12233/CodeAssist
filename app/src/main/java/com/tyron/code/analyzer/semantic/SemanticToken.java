package com.tyron.code.analyzer.semantic;

import androidx.annotation.NonNull;

import com.tyron.code.language.java.JavaSemanticHighlighter;

public class SemanticToken {
    private final TokenType tokenType;
    private final int tokenModifiers;
    private final int offset;
    private final int length;

    public SemanticToken(int offset, int length, TokenType tokenType, int tokenModifiers) {
        this.offset = offset;
        this.length = length;
        this.tokenType = tokenType;
        this.tokenModifiers = tokenModifiers;
    }

    public TokenType getTokenType() {
        return tokenType;
    }

    public int getTokenModifiers() {
        return tokenModifiers;
    }

    public int getOffset() {
        return offset;
    }

    public int getLength() {
        return length;
    }

    @NonNull
    @Override
    public String toString() {
        return "SemanticToken{" +
               "tokenType=" +
               tokenType +
               ", tokenModifiers=" +
               tokenModifiers +
               ", offset=" +
               offset +
               ", length=" +
               length +
               '}';
    }
}