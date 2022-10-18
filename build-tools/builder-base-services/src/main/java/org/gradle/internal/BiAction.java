package org.gradle.internal;

public interface BiAction<A, B> {

    void execute(A a, B b);
}
