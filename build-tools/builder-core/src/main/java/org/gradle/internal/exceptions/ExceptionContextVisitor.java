package org.gradle.internal.exceptions;

import org.gradle.util.internal.TreeVisitor;

public abstract class ExceptionContextVisitor extends TreeVisitor<Throwable> {
    protected abstract void visitCause(Throwable cause);

    protected abstract void visitLocation(String location);
}