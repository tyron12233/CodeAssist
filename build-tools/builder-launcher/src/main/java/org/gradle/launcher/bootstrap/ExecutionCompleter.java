package org.gradle.launcher.bootstrap;

public interface ExecutionCompleter {
    void complete();
    void completeWithFailure(Throwable t);
}