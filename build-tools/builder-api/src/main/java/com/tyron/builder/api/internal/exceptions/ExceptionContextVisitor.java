package com.tyron.builder.api.internal.exceptions;

import com.tyron.builder.util.internal.TreeVisitor;

public abstract class ExceptionContextVisitor extends TreeVisitor<Throwable> {
    public abstract void visitCause(Throwable cause);

    public abstract void visitLocation(String location);
}
