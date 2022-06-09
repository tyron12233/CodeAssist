package com.tyron.builder.workers.internal;

import com.tyron.builder.process.JavaForkOptions;
import com.tyron.builder.process.internal.JavaForkOptionsFactory;
import com.tyron.builder.process.internal.JavaForkOptionsInternal;

public class DaemonForkOptionsBuilder {
    private final JavaForkOptionsInternal javaForkOptions;
    private final JavaForkOptionsFactory forkOptionsFactory;
    private KeepAliveMode keepAliveMode = KeepAliveMode.DAEMON;
    private ClassLoaderStructure classLoaderStructure = null;

    public DaemonForkOptionsBuilder(JavaForkOptionsFactory forkOptionsFactory) {
        this.forkOptionsFactory = forkOptionsFactory;
        this.javaForkOptions = forkOptionsFactory.newJavaForkOptions();
    }

    public DaemonForkOptionsBuilder keepAliveMode(KeepAliveMode keepAliveMode) {
        this.keepAliveMode = keepAliveMode;
        return this;
    }

    public DaemonForkOptionsBuilder javaForkOptions(JavaForkOptions javaForkOptions) {
        javaForkOptions.copyTo(this.javaForkOptions);
        return this;
    }

    public DaemonForkOptionsBuilder withClassLoaderStructure(ClassLoaderStructure classLoaderStructure) {
        this.classLoaderStructure = classLoaderStructure;
        return this;
    }

    public DaemonForkOptions build() {
        return new DaemonForkOptions(buildJavaForkOptions(), keepAliveMode, classLoaderStructure);
    }

    private JavaForkOptionsInternal buildJavaForkOptions() {
        return forkOptionsFactory.immutableCopy(javaForkOptions);
    }
}
