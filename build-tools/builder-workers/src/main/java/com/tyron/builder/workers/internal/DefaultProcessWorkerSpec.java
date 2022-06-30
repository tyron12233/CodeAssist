package com.tyron.builder.workers.internal;

import com.google.common.collect.Maps;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.process.JavaForkOptions;
import com.tyron.builder.workers.ClassLoaderWorkerSpec;
import com.tyron.builder.workers.ProcessWorkerSpec;

import javax.inject.Inject;

public class DefaultProcessWorkerSpec extends DefaultClassLoaderWorkerSpec implements ProcessWorkerSpec, ClassLoaderWorkerSpec {
    protected final JavaForkOptions forkOptions;

    @Inject
    public DefaultProcessWorkerSpec(JavaForkOptions forkOptions, ObjectFactory objectFactory) {
        super(objectFactory);
        this.forkOptions = forkOptions;
        this.forkOptions.setEnvironment(Maps.newHashMap());
    }

    @Override
    public JavaForkOptions getForkOptions() {
        return forkOptions;
    }

    @Override
    public void forkOptions(Action<? super JavaForkOptions> forkOptionsAction) {
        forkOptionsAction.execute(forkOptions);
    }
}
