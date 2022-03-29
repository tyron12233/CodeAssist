package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.internal.provider.DefaultPropertyFactory;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;

import java.io.File;

public class WorkerSharedProjectScopeServices {
    private final File projectDir;

    public WorkerSharedProjectScopeServices(File projectDir) {
        this.projectDir = projectDir;
    }

    void configure(ServiceRegistration registration) {
        registration.add(DefaultPropertyFactory.class);
    //        registration.add(DefaultFilePropertyFactory.class);
    //        registration.add(DefaultFileCollectionFactory.class);
    }

}
