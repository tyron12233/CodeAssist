package com.tyron.builder.api.internal.tasks.compile;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.tasks.compile.BaseForkOptions;
import com.tyron.builder.process.JavaForkOptions;
import com.tyron.builder.process.internal.JavaForkOptionsFactory;

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
