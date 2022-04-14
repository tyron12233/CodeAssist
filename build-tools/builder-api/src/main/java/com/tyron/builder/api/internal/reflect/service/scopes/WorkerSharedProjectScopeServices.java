package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.Factory;
import com.tyron.builder.api.internal.file.DefaultFileCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFileOperations;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.DefaultProjectLayout;
import com.tyron.builder.api.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileFactory;
import com.tyron.builder.api.internal.file.FileOperations;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.hash.FileHasher;
import com.tyron.builder.api.internal.hash.StreamHasher;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.provider.DefaultPropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.reflect.DirectInstantiator;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.providers.ProviderFactory;
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
