/*
 * Copyright 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tyron.builder.api.internal.artifacts.repositories;

import com.google.common.base.Suppliers;
import com.tyron.builder.api.internal.artifacts.repositories.resolver.ExternalRepositoryResourceAccessor;
import com.tyron.builder.api.internal.artifacts.repositories.transport.RepositoryTransport;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.ActionConfiguration;
import com.tyron.builder.api.NamedDomainObjectCollection;
import com.tyron.builder.api.artifacts.ComponentMetadataListerDetails;
import com.tyron.builder.api.artifacts.ComponentMetadataSupplier;
import com.tyron.builder.api.artifacts.ComponentMetadataSupplierDetails;
import com.tyron.builder.api.artifacts.ComponentMetadataVersionLister;
import com.tyron.builder.api.artifacts.repositories.ArtifactRepository;
import com.tyron.builder.api.artifacts.repositories.MetadataSupplierAware;
import com.tyron.builder.api.artifacts.repositories.RepositoryContentDescriptor;
import com.tyron.builder.api.artifacts.repositories.RepositoryResourceAccessor;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.internal.UncheckedException;
import com.tyron.builder.internal.action.ConfigurableRule;
import com.tyron.builder.internal.action.DefaultConfigurableRule;
import com.tyron.builder.internal.action.DefaultConfigurableRules;
import com.tyron.builder.internal.action.InstantiatingAction;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.resolve.caching.ImplicitInputsCapturingInstantiator;
import com.tyron.builder.internal.resource.local.FileStore;
import com.tyron.builder.internal.service.DefaultServiceRegistry;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.function.Supplier;

public abstract class AbstractArtifactRepository implements ArtifactRepositoryInternal, ContentFilteringRepository, MetadataSupplierAware {
    private String name;
    private boolean isPartOfContainer;
    private Class<? extends ComponentMetadataSupplier> componentMetadataSupplierRuleClass;
    private Class<? extends ComponentMetadataVersionLister> componentMetadataListerRuleClass;
    private Action<? super ActionConfiguration> componentMetadataSupplierRuleConfiguration;
    private Action<? super ActionConfiguration> componentMetadataListerRuleConfiguration;
    private final ObjectFactory objectFactory;
    private final Supplier<RepositoryContentDescriptorInternal> repositoryContentDescriptor = Suppliers.memoize(this::createRepositoryDescriptor)::get;

    protected AbstractArtifactRepository(ObjectFactory objectFactory) {
       this.objectFactory = objectFactory;
    }

    @Override
    public void onAddToContainer(NamedDomainObjectCollection<ArtifactRepository> container) {
        isPartOfContainer = true;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void setName(String name) {
        if (isPartOfContainer) {
            throw new IllegalStateException("The name of an ArtifactRepository cannot be changed after it has been added to a repository container. You should set the name when creating the repository.");
        }
        this.name = name;
    }

    @Override
    public String getDisplayName() {
        return getName();
    }

    @Override
    public void setMetadataSupplier(Class<? extends ComponentMetadataSupplier> ruleClass) {
        this.componentMetadataSupplierRuleClass = ruleClass;
        this.componentMetadataSupplierRuleConfiguration = null;
    }

    @Override
    public void setMetadataSupplier(Class<? extends ComponentMetadataSupplier> rule, Action<? super ActionConfiguration> configureAction) {
        this.componentMetadataSupplierRuleClass = rule;
        this.componentMetadataSupplierRuleConfiguration = configureAction;
    }

    @Override
    public void setComponentVersionsLister(Class<? extends ComponentMetadataVersionLister> lister) {
        this.componentMetadataListerRuleClass = lister;
        this.componentMetadataListerRuleConfiguration = null;
    }

    @Override
    public void setComponentVersionsLister(Class<? extends ComponentMetadataVersionLister> lister, Action<? super ActionConfiguration> configureAction) {
        this.componentMetadataListerRuleClass = lister;
        this.componentMetadataListerRuleConfiguration = configureAction;
    }

    @Override
    public RepositoryContentDescriptorInternal createRepositoryDescriptor() {
        return new DefaultRepositoryContentDescriptor(this::getDisplayName);
    }

    @Override
    public RepositoryContentDescriptorInternal getRepositoryDescriptorCopy() {
        return repositoryContentDescriptor.get().asMutableCopy();
    }

    RepositoryContentDescriptorInternal getRepositoryDescriptor() {
        return repositoryContentDescriptor.get();
    }

    @Override
    public Action<? super ArtifactResolutionDetails> getContentFilter() {
        return repositoryContentDescriptor.get().toContentFilter();
    }

    @Override
    public void content(Action<? super RepositoryContentDescriptor> configureAction) {
        configureAction.execute(repositoryContentDescriptor.get());
    }

    @Nullable
    InstantiatingAction<ComponentMetadataSupplierDetails> createComponentMetadataSupplierFactory(Instantiator instantiator, IsolatableFactory isolatableFactory) {
        if (componentMetadataSupplierRuleClass != null) {
            return createRuleAction(instantiator, DefaultConfigurableRule.of(componentMetadataSupplierRuleClass, componentMetadataSupplierRuleConfiguration, isolatableFactory));
        } else {
            return null;
        }
    }

    @Nullable
    InstantiatingAction<ComponentMetadataListerDetails> createComponentMetadataVersionLister(Instantiator instantiator, IsolatableFactory isolatableFactory) {
        if (componentMetadataListerRuleClass != null) {
            return createRuleAction(instantiator, DefaultConfigurableRule.of(componentMetadataListerRuleClass, componentMetadataListerRuleConfiguration, isolatableFactory));
        } else {
            return null;
        }
    }

    /**
     * Creates a service registry giving access to the services we want to expose to rules and returns an instantiator that uses this service registry.
     *
     * @param transport the transport used to create the repository accessor
     * @return a dependency injecting instantiator, aware of services we want to expose
     */
    ImplicitInputsCapturingInstantiator createInjectorForMetadataSuppliers(final RepositoryTransport transport, InstantiatorFactory instantiatorFactory, final URI rootUri, final FileStore<String> externalResourcesFileStore) {
        DefaultServiceRegistry registry = new DefaultServiceRegistry();
        registry.addProvider(new Object() {
            RepositoryResourceAccessor createResourceAccessor() {
                return createRepositoryAccessor(transport, rootUri, externalResourcesFileStore);
            }
        });
        registry.add(ObjectFactory.class, objectFactory);
        return new ImplicitInputsCapturingInstantiator(registry, instantiatorFactory);
    }

    protected RepositoryResourceAccessor createRepositoryAccessor(RepositoryTransport transport, URI rootUri, FileStore<String> externalResourcesFileStore) {
        return new ExternalRepositoryResourceAccessor(rootUri, transport.getResourceAccessor(), externalResourcesFileStore);
    }


    private static <T> InstantiatingAction<T> createRuleAction(final Instantiator instantiator, final ConfigurableRule<T> rule) {
        return new InstantiatingAction<>(DefaultConfigurableRules.of(rule), instantiator, (target, throwable) -> {
            throw UncheckedException.throwAsUncheckedException(throwable);
        });
    }
}
