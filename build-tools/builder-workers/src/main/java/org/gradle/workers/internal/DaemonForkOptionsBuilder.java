package org.gradle.workers.internal;

import org.gradle.process.JavaForkOptions;
import org.gradle.process.internal.JavaForkOptionsFactory;
import org.gradle.process.internal.JavaForkOptionsInternal;

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
