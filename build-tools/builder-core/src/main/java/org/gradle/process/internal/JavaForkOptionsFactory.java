package org.gradle.process.internal;

public interface JavaForkOptionsFactory {
    JavaForkOptionsInternal newDecoratedJavaForkOptions();
    JavaForkOptionsInternal newJavaForkOptions();

    JavaForkOptionsInternal immutableCopy(JavaForkOptionsInternal options);
}
