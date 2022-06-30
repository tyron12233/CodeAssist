package com.tyron.builder.internal;

public interface BiAction<A, B> {

    void execute(A a, B b);
}
