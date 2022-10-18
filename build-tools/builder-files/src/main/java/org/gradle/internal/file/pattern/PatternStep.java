package org.gradle.internal.file.pattern;

public interface PatternStep {
    boolean matches(String candidate);
}