package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFileCollectionFactory;
import com.tyron.builder.api.internal.file.DefaultFileOperations;
import com.tyron.builder.api.internal.file.DefaultFilePropertyFactory;
import com.tyron.builder.api.internal.file.DefaultProjectLayout;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileFactory;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.model.DefaultObjectFactory;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.internal.provider.DefaultPropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.internal.resources.DefaultResourceHandler;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.hash.FileHasher;
import com.tyron.builder.internal.hash.StreamHasher;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;

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

    protected FileResolver createFileResolver(FileLookup lookup) {
        return lookup.getFileResolver(projectDir);
    }

    DefaultProjectLayout createProjectLayout(FileResolver fileResolver,
                                             FileCollectionFactory fileCollectionFactory,
                                             TaskDependencyFactory taskDependencyFactory,
                                             FilePropertyFactory filePropertyFactory,
                                             Factory<PatternSet> patternSetFactory,
                                             PropertyHost propertyHost,
                                             FileFactory fileFactory) {
        return new DefaultProjectLayout(projectDir, fileResolver, taskDependencyFactory,
                patternSetFactory, propertyHost, fileCollectionFactory, filePropertyFactory,
                fileFactory);
    }

    protected DefaultFileOperations createFileOperations(
            FileResolver fileResolver,
            TemporaryFileProvider temporaryFileProvider,
            Instantiator instantiator,
            DirectoryFileTreeFactory directoryFileTreeFactory,
            StreamHasher streamHasher,
            FileHasher fileHasher,
            DefaultResourceHandler.Factory resourceHandlerFactory,
            FileCollectionFactory fileCollectionFactory,
            ObjectFactory objectFactory,
            FileSystem fileSystem,
            Factory<PatternSet> patternSetFactory,
            Deleter deleter,
            DocumentationRegistry documentationRegistry,
            ProviderFactory providers
    ) {
        return new DefaultFileOperations(
                fileResolver,
                temporaryFileProvider,
                instantiator,
                directoryFileTreeFactory,
                streamHasher,
                fileHasher,
                resourceHandlerFactory,
                fileCollectionFactory,
                objectFactory,
                fileSystem,
                patternSetFactory,
                deleter,
                documentationRegistry,
                providers);
    }

    ObjectFactory createObjectFactory(InstantiatorFactory instantiatorFactory, ServiceRegistry services, Factory<PatternSet> patternSetFactory, DirectoryFileTreeFactory directoryFileTreeFactory,
                                      PropertyFactory propertyFactory, FilePropertyFactory filePropertyFactory, FileCollectionFactory fileCollectionFactory,
                                      DomainObjectCollectionFactory domainObjectCollectionFactory, NamedObjectInstantiator namedObjectInstantiator) {
        return new DefaultObjectFactory(
                instantiatorFactory.decorate(services),
                namedObjectInstantiator,
                directoryFileTreeFactory,
                patternSetFactory,
                propertyFactory,
                filePropertyFactory,
                fileCollectionFactory,
                domainObjectCollectionFactory);
    }
}
