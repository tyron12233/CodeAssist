package org.gradle.launcher.bootstrap;

public class ProcessCompleter implements ExecutionCompleter {
    @Override
    public void complete() {
        System.exit(0);
    }

    @Override
    public void completeWithFailure(Throwable t) {
        System.exit(1);
    }
}
