package com.tyron.builder.api.internal.logging;

public interface DiagnosticsVisitor {
    DiagnosticsVisitor node(String message);

    DiagnosticsVisitor startChildren();

    DiagnosticsVisitor endChildren();
}