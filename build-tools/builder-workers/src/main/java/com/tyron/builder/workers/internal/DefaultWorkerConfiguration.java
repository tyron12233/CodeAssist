package com.tyron.builder.workers.internal;

import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.ActionConfiguration;
import com.tyron.builder.api.internal.DefaultActionConfiguration;
import com.tyron.builder.process.JavaForkOptions;
import com.tyron.builder.process.internal.JavaForkOptionsFactory;
import com.tyron.builder.util.internal.GUtil;
import com.tyron.builder.workers.ClassLoaderWorkerSpec;
import com.tyron.builder.workers.ProcessWorkerSpec;
import com.tyron.builder.workers.WorkerSpec;

import java.io.File;
import java.util.List;

@SuppressWarnings("deprecation")
public class DefaultWorkerConfiguration extends DefaultActionConfiguration implements com.tyron.builder.workers.WorkerConfiguration {
    private final ActionConfiguration actionConfiguration = new DefaultActionConfiguration();
    private final JavaForkOptionsFactory forkOptionsFactory;
    private com.tyron.builder.workers.IsolationMode isolationMode = com.tyron.builder.workers.IsolationMode.AUTO;
    private JavaForkOptions forkOptions;
    private String displayName;
    private List<File> classpath = Lists.newArrayList();

    public DefaultWorkerConfiguration(JavaForkOptionsFactory forkOptionsFactory) {
        this.forkOptionsFactory = forkOptionsFactory;
    }

    @Override
    public com.tyron.builder.workers.IsolationMode getIsolationMode() {
        return isolationMode;
    }

    @Override
    public void setIsolationMode(com.tyron.builder.workers.IsolationMode isolationMode) {
        this.isolationMode = isolationMode == null ? com.tyron.builder.workers.IsolationMode.AUTO : isolationMode;
    }

    @Override
    public void forkOptions(Action<? super JavaForkOptions> forkOptionsAction) {
        forkOptionsAction.execute(getForkOptions());
    }

    @Override
    public JavaForkOptions getForkOptions() {
        if (forkOptions == null) {
            forkOptions = forkOptionsFactory.newDecoratedJavaForkOptions();
        }
        return forkOptions;
    }

    @Override
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Iterable<File> getClasspath() {
        return classpath;
    }

    @Override
    public void setClasspath(Iterable<File> classpath) {
        this.classpath = Lists.newArrayList(classpath);
    }

    @Override
    public void classpath(Iterable<File> files) {
        GUtil.addToCollection(classpath, files);
    }

    @Override
    public void params(Object... params) {
        actionConfiguration.params(params);
    }

    @Override
    public void setParams(Object... params) {
        actionConfiguration.setParams(params);
    }

    @Override
    public Object[] getParams() {
        return actionConfiguration.getParams();
    }

    @Override
    public com.tyron.builder.workers.ForkMode getForkMode() {
        switch (getIsolationMode()) {
            case AUTO:
                return com.tyron.builder.workers.ForkMode.AUTO;
            case NONE:
            case CLASSLOADER:
                return com.tyron.builder.workers.ForkMode.NEVER;
            case PROCESS:
                return com.tyron.builder.workers.ForkMode.ALWAYS;
            default:
                throw new IllegalStateException();
        }
    }

    @Override
    public void setForkMode(com.tyron.builder.workers.ForkMode forkMode) {
        switch (forkMode) {
            case AUTO:
                setIsolationMode(com.tyron.builder.workers.IsolationMode.AUTO);
                break;
            case NEVER:
                setIsolationMode(com.tyron.builder.workers.IsolationMode.CLASSLOADER);
                break;
            case ALWAYS:
                setIsolationMode(com.tyron.builder.workers.IsolationMode.PROCESS);
                break;
        }
    }

    void adaptTo(WorkerSpec workerSpec) {
        if (workerSpec instanceof ClassLoaderWorkerSpec) {
            ClassLoaderWorkerSpec classLoaderWorkerSpec = (ClassLoaderWorkerSpec) workerSpec;
            classLoaderWorkerSpec.getClasspath().from(getClasspath());
        }

        if (workerSpec instanceof ProcessWorkerSpec) {
            ProcessWorkerSpec processWorkerSpec = (ProcessWorkerSpec) workerSpec;
            processWorkerSpec.getClasspath().from(getClasspath());
            getForkOptions().copyTo(processWorkerSpec.getForkOptions());
        }
    }

}
