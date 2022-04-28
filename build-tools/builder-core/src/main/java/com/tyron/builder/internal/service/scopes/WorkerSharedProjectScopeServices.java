package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.internal.Factory;
import com.tyron.builder.api.internal.file.DefaultFileCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.DefaultProjectLayout;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileFactory;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.provider.DefaultPropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.tasks.util.PatternSet;

import java.io.File;

public class WorkerSharedProjectScopeServices {
    private final File projectDir;

    public WorkerSharedProjectScopeServices(File projectDir) {
        this.projectDir = projectDir;
    }

    void configure(ServiceRegistration registration) {
        registration.add(DefaultPropertyFactory.class);
            registration.add(DefaultFilePropertyFactory.class);
            registration.add(DefaultFileCollectionFactory.class);
    }

    DefaultProjectLayout createProjectLayout(FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, TaskDependencyFactory taskDependencyFactory,
                                             FilePropertyFactory filePropertyFactory, Factory<PatternSet> patternSetFactory, PropertyHost propertyHost, FileFactory fileFactory) {
        return new DefaultProjectLayout(projectDir, fileResolver, taskDependencyFactory, patternSetFactory, propertyHost, fileCollectionFactory, filePropertyFactory, fileFactory);
    }
}
