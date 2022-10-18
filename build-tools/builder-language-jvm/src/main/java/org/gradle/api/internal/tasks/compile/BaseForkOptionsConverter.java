package org.gradle.api.internal.tasks.compile;

import org.gradle.api.Transformer;
import org.gradle.api.tasks.compile.BaseForkOptions;
import org.gradle.process.JavaForkOptions;
import org.gradle.process.internal.JavaForkOptionsFactory;

public class BaseForkOptionsConverter implements Transformer<JavaForkOptions, BaseForkOptions> {
    private final JavaForkOptionsFactory forkOptionsFactory;

    public BaseForkOptionsConverter(JavaForkOptionsFactory forkOptionsFactory) {
        this.forkOptionsFactory = forkOptionsFactory;
    }

    @Override
    public JavaForkOptions transform(BaseForkOptions baseForkOptions) {
        JavaForkOptions javaForkOptions = forkOptionsFactory.newJavaForkOptions();
        javaForkOptions.setMinHeapSize(baseForkOptions.getMemoryInitialSize());
        javaForkOptions.setMaxHeapSize(baseForkOptions.getMemoryMaximumSize());
        javaForkOptions.setJvmArgs(baseForkOptions.getJvmArgs());
        return javaForkOptions;
    }
}
