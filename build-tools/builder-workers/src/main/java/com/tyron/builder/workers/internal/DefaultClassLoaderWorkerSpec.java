package com.tyron.builder.workers.internal;

import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.workers.ClassLoaderWorkerSpec;

import javax.inject.Inject;

public class DefaultClassLoaderWorkerSpec extends DefaultWorkerSpec implements ClassLoaderWorkerSpec {
    private final ConfigurableFileCollection classpath;

    @Inject
    public DefaultClassLoaderWorkerSpec(ObjectFactory objectFactory) {
        this.classpath = objectFactory.fileCollection();
    }

    @Override
    public ConfigurableFileCollection getClasspath() {
        return classpath;
    }
}
