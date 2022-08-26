package org.gradle.plugins.ide.idea.model.internal;

/**
 * The classpath scopes available in IntelliJ IDEA.
 */
public enum GeneratedIdeaScope {
    PROVIDED,
    COMPILE,
    RUNTIME,
    TEST;

    public static GeneratedIdeaScope nullSafeValueOf(String scope) {
        return scope == null ? COMPILE : valueOf(scope);
    }
}
