package org.gradle.initialization;

public class NoOpBuildEventConsumer implements BuildEventConsumer {
    @Override
    public void dispatch(Object message) {
    }
}