package org.gradle.workers.internal;

import com.google.common.collect.Maps;
import org.gradle.api.Action;
import org.gradle.api.model.ObjectFactory;
import org.gradle.process.JavaForkOptions;
import org.gradle.workers.ClassLoaderWorkerSpec;
import org.gradle.workers.ProcessWorkerSpec;

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
