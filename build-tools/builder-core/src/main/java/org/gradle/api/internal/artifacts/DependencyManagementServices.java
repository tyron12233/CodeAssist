package org.gradle.api.internal.artifacts;

import org.gradle.api.internal.DomainObjectContext;
import org.gradle.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import org.gradle.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.internal.service.ServiceRegistration;

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
