package org.gradle.internal;

public interface TriAction<A, B, C> {

    void execute(A a, B b, C c);
}
