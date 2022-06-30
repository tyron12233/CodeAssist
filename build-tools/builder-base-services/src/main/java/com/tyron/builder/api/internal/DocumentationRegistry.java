package com.tyron.builder.api.internal;

import com.tyron.builder.util.GradleVersion;

import org.jetbrains.annotations.NotNull;

public class DocumentationRegistry {

    private final GradleVersion gradleVersion;

    public DocumentationRegistry() {
        this.gradleVersion = GradleVersion.current();
    }

    /**
     * Returns the location of the documentation for the given feature, referenced by id. The location may be local or remote.
     */
    public String getDocumentationFor(String id) {
        return String.format("https://docs.gradle.org/%s/userguide/%s.html", gradleVersion.getVersion(), id);
    }

    public String getDocumentationFor(String id, String section) {
        return String.format("https://docs.gradle.org/%s/userguide/%s.html#%s", gradleVersion.getVersion(), id, section);
    }

    public String getDslRefForProperty(Class<?> clazz, String property) {
        String className = clazz.getName();
        return String.format("https://docs.gradle.org/%s/dsl/%s.html#%s:%s", gradleVersion.getVersion(), className, className, property);
    }

    public String getSampleIndex() {
        return String.format("https://docs.gradle.org/%s/samples", gradleVersion.getVersion());
    }

    public String getSampleFor(String id) {
        return String.format("https://docs.gradle.org/%s/samples/sample_%s.html", gradleVersion.getVersion(), id);
    }
}