package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.internal.service.ServiceRegistration;

/**
 * Factory for various types related to dependency management.
 */
public interface DependencyManagementServices {
    /**
     * Registers the dependency management DSL services.
     */
    void addDslServices(ServiceRegistration registration, DomainObjectContext domainObjectContext);

    DependencyResolutionServices create(FileResolver resolver, FileCollectionFactory fileCollectionFactory, DependencyMetaDataProvider dependencyMetaDataProvider,
                                        ProjectFinder projectFinder, DomainObjectContext domainObjectContext);
}
