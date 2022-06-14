package com.tyron.builder.plugin.use.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.NamedDomainObjectCollection;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.dsl.DependencyHandler;
import com.tyron.builder.api.artifacts.dsl.DependencyLockingHandler;
import com.tyron.builder.api.artifacts.dsl.RepositoryHandler;
import com.tyron.builder.api.artifacts.repositories.ArtifactRepository;
import com.tyron.builder.api.artifacts.repositories.RepositoryContentDescriptor;
import com.tyron.builder.api.attributes.AttributesSchema;
import com.tyron.builder.api.internal.artifacts.DependencyResolutionServices;
import com.tyron.builder.api.internal.artifacts.JavaEcosystemSupport;
import com.tyron.builder.api.internal.artifacts.dsl.RepositoryHandlerInternal;
import com.tyron.builder.api.internal.artifacts.ivyservice.ivyresolve.ConfiguredModuleComponentRepository;
import com.tyron.builder.api.internal.artifacts.repositories.ArtifactRepositoryInternal;
import com.tyron.builder.api.internal.artifacts.repositories.ArtifactResolutionDetails;
import com.tyron.builder.api.internal.artifacts.repositories.ContentFilteringRepository;
import com.tyron.builder.api.internal.artifacts.repositories.RepositoryContentDescriptorInternal;
import com.tyron.builder.api.internal.artifacts.repositories.ResolutionAwareRepository;
import com.tyron.builder.api.internal.artifacts.repositories.descriptor.RepositoryDescriptor;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.plugin.use.resolve.internal.PluginRepositoriesProvider;

import java.util.List;
import java.util.stream.Collectors;

public class PluginDependencyResolutionServices implements DependencyResolutionServices {

    private static final String REPOSITORY_NAME_PREFIX = "__plugin_repository__";

    private final Factory<DependencyResolutionServices> factory;
    private DependencyResolutionServices dependencyResolutionServices;

    public PluginDependencyResolutionServices(Factory<DependencyResolutionServices> factory) {
        this.factory = factory;
    }

    private DependencyResolutionServices getDependencyResolutionServices() {
        if (dependencyResolutionServices == null) {
            dependencyResolutionServices = factory.create();
        }
        return dependencyResolutionServices;
    }

    @Override
    public RepositoryHandler getResolveRepositoryHandler() {
        return getDependencyResolutionServices().getResolveRepositoryHandler();
    }

    @Override
    public ConfigurationContainer getConfigurationContainer() {
        return getDependencyResolutionServices().getConfigurationContainer();
    }

    @Override
    public DependencyHandler getDependencyHandler() {
        return getDependencyResolutionServices().getDependencyHandler();
    }

    @Override
    public DependencyLockingHandler getDependencyLockingHandler() {
        return getDependencyResolutionServices().getDependencyLockingHandler();
    }

    @Override
    public ImmutableAttributesFactory getAttributesFactory() {
        return getDependencyResolutionServices().getAttributesFactory();
    }

    @Override
    public AttributesSchema getAttributesSchema() {
        return getDependencyResolutionServices().getAttributesSchema();
    }

    public PluginRepositoryHandlerProvider getPluginRepositoryHandlerProvider() {
        return this::getResolveRepositoryHandler;
    }

    @Override
    public ObjectFactory getObjectFactory() {
        return getDependencyResolutionServices().getObjectFactory();
    }

    public PluginRepositoriesProvider getPluginRepositoriesProvider() {
        return new DefaultPluginRepositoriesProvider();
    }

    private class DefaultPluginRepositoriesProvider implements PluginRepositoriesProvider {
        private final Object lock = new Object();
        private List<ArtifactRepository> repositories;

        @Override
        public void prepareForPluginResolution() {
            synchronized (lock) {
                if (repositories == null) {
                    DependencyResolutionServices dependencyResolutionServices = getDependencyResolutionServices();
                    RepositoryHandler repositories = getResolveRepositoryHandler();
                    if (repositories.isEmpty()) {
                        repositories.gradlePluginPortal();
                    }
                    JavaEcosystemSupport.configureSchema(dependencyResolutionServices.getAttributesSchema(), dependencyResolutionServices.getObjectFactory());
                    this.repositories = getResolveRepositoryHandler().stream().map(PluginArtifactRepository::new).collect(Collectors.toList());
                }
            }
        }

        @Override
        public List<ArtifactRepository> getPluginRepositories() {
            synchronized (lock) {
                if (repositories == null) {
                    throw new IllegalStateException("Plugin repositories have not been prepared.");
                }
                return repositories;
            }
        }

        @Override
        public boolean isExclusiveContentInUse() {
            return ((RepositoryHandlerInternal) getResolveRepositoryHandler()).isExclusiveContentInUse();
        }
    }


    private static class PluginArtifactRepository implements ArtifactRepositoryInternal, ContentFilteringRepository, ResolutionAwareRepository {
        private final ArtifactRepositoryInternal delegate;
        private final ResolutionAwareRepository resolutionAwareDelegate;
        private final RepositoryContentDescriptorInternal repositoryContentDescriptor;

        private PluginArtifactRepository(ArtifactRepository delegate) {
            this.delegate = (ArtifactRepositoryInternal) delegate;
            this.resolutionAwareDelegate = (ResolutionAwareRepository) delegate;
            this.repositoryContentDescriptor = this.delegate.getRepositoryDescriptorCopy();
        }

        @Override
        public String getName() {
            return REPOSITORY_NAME_PREFIX + delegate.getName();
        }

        @Override
        public void setName(String name) {
            delegate.setName(name);
        }

        @Override
        public void content(Action<? super RepositoryContentDescriptor> configureAction) {
            configureAction.execute(repositoryContentDescriptor);
        }

        @Override
        public Action<? super ArtifactResolutionDetails> getContentFilter() {
            return repositoryContentDescriptor.toContentFilter();
        }

        @Override
        public String getDisplayName() {
            return delegate.getDisplayName();
        }

        @Override
        public ConfiguredModuleComponentRepository createResolver() {
            return resolutionAwareDelegate.createResolver();
        }

        @Override
        public RepositoryDescriptor getDescriptor() {
            return resolutionAwareDelegate.getDescriptor();
        }

        @Override
        public void onAddToContainer(NamedDomainObjectCollection<ArtifactRepository> container) {
            delegate.onAddToContainer(container);
        }

        @Override
        public RepositoryContentDescriptorInternal createRepositoryDescriptor() {
            return delegate.createRepositoryDescriptor();
        }

        @Override
        public RepositoryContentDescriptorInternal getRepositoryDescriptorCopy() {
            return repositoryContentDescriptor.asMutableCopy();
        }
    }
}
