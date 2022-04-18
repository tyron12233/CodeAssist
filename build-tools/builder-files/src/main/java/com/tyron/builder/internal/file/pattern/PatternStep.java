package com.tyron.builder.internal.file.pattern;

public interface PatternStep {
    boolean matches(String candidate);
}