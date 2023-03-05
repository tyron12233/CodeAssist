package org.gradle.workers.internal;

import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.workers.ClassLoaderWorkerSpec;

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
