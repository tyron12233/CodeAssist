package org.gradle.internal.logging.text;

public interface DiagnosticsVisitor {
    DiagnosticsVisitor node(String message);

    DiagnosticsVisitor startChildren();

    DiagnosticsVisitor endChildren();
}