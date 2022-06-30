package com.tyron.builder.internal.exceptions;

import com.tyron.builder.util.internal.TreeVisitor;

public abstract class ExceptionContextVisitor extends TreeVisitor<Throwable> {
    protected abstract void visitCause(Throwable cause);

    protected abstract void visitLocation(String location);
}