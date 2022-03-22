package com.tyron.builder.api.internal.file.pattern;

public interface PatternStep {
    boolean matches(String candidate);
}