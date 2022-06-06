package com.tyron.builder.workers.internal;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.tyron.builder.process.JavaForkOptions;
import com.tyron.builder.process.internal.JavaForkOptionsInternal;

public class DaemonForkOptions {
    private final JavaForkOptionsInternal forkOptions;
    private final KeepAliveMode keepAliveMode;
    private final ClassLoaderStructure classLoaderStructure;

    DaemonForkOptions(JavaForkOptionsInternal forkOptions,
                      KeepAliveMode keepAliveMode,
                      ClassLoaderStructure classLoaderStructure) {
        this.forkOptions = forkOptions;
        this.keepAliveMode = keepAliveMode;
        this.classLoaderStructure = classLoaderStructure;
    }

    public KeepAliveMode getKeepAliveMode() {
        return keepAliveMode;
    }

    public JavaForkOptions getJavaForkOptions() {
        return forkOptions;
    }

    public ClassLoaderStructure getClassLoaderStructure() {
        return classLoaderStructure;
    }

    public boolean isCompatibleWith(DaemonForkOptions other) {
        return forkOptions.isCompatibleWith(other.forkOptions)
                && keepAliveMode == other.getKeepAliveMode()
                && Objects.equal(classLoaderStructure, other.getClassLoaderStructure());
    }

    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("executable", forkOptions.getExecutable())
                .add("minHeapSize", forkOptions.getMinHeapSize())
                .add("maxHeapSize", forkOptions.getMaxHeapSize())
                .add("jvmArgs", forkOptions.getJvmArgs())
                .add("keepAliveMode", keepAliveMode)
                .toString();
    }
}
