/*
 * Copyright 2017 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts.repositories.resolver;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencyMetadata;
import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.MutableVersionConstraint;
import com.tyron.builder.api.artifacts.VersionConstraint;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.internal.artifacts.dependencies.DefaultMutableVersionConstraint;
import com.tyron.builder.api.internal.attributes.AttributeContainerInternal;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.component.external.model.DefaultModuleComponentSelector;
import com.tyron.builder.internal.component.external.model.ModuleDependencyMetadata;
import com.tyron.builder.internal.component.model.ForcingDependencyMetadata;

import java.util.List;

public abstract class AbstractDependencyMetadataAdapter<T extends DependencyMetadata<T>> implements DependencyMetadata<T> {
    private final List<ModuleDependencyMetadata> container;
    private final int originalIndex;
    private final ImmutableAttributesFactory attributesFactory;

    public AbstractDependencyMetadataAdapter(ImmutableAttributesFactory attributesFactory, List<ModuleDependencyMetadata> container, int originalIndex) {
        this.attributesFactory = attributesFactory;
        this.container = container;
        this.originalIndex = originalIndex;
    }

    protected ModuleDependencyMetadata getOriginalMetadata() {
        return container.get(originalIndex);
    }

    protected void updateMetadata(ModuleDependencyMetadata modifiedMetadata) {
        container.set(originalIndex, modifiedMetadata);
    }

    @Override
    public String getGroup() {
        return getOriginalMetadata().getSelector().getGroup();
    }

    @Override
    public String getName() {
        return getOriginalMetadata().getSelector().getModule();
    }

    @Override
    public VersionConstraint getVersionConstraint() {
        return getOriginalMetadata().getSelector().getVersionConstraint();
    }

    @Override
    public T version(Action<? super MutableVersionConstraint> configureAction) {
        DefaultMutableVersionConstraint mutableVersionConstraint = new DefaultMutableVersionConstraint(getVersionConstraint());
        configureAction.execute(mutableVersionConstraint);
        ModuleDependencyMetadata dependencyMetadata = getOriginalMetadata().withRequestedVersion(mutableVersionConstraint);
        updateMetadata(dependencyMetadata);
        return Cast.uncheckedCast(this);
    }

    @Override
    public T because(String reason) {
        updateMetadata(getOriginalMetadata().withReason(reason));
        return Cast.uncheckedCast(this);
    }

    @Override
    public ModuleIdentifier getModule() {
        return getOriginalMetadata().getSelector().getModuleIdentifier();
    }

    @Override
    public String getReason() {
        return getOriginalMetadata().getReason();
    }

    @Override
    public String toString() {
        return getGroup() + ":" + getName() + ":" + getVersionConstraint();
    }

    @Override
    public AttributeContainer getAttributes() {
        return getOriginalMetadata().getSelector().getAttributes();
    }

    @Override
    public T attributes(Action<? super AttributeContainer> configureAction) {
        ModuleComponentSelector selector = getOriginalMetadata().getSelector();
        AttributeContainerInternal attributes = attributesFactory.mutable((AttributeContainerInternal) selector.getAttributes());
        configureAction.execute(attributes);
        ModuleComponentSelector target = DefaultModuleComponentSelector.newSelector(selector.getModuleIdentifier(), selector.getVersionConstraint(), attributes.asImmutable(), selector.getRequestedCapabilities());
        ModuleDependencyMetadata metadata = (ModuleDependencyMetadata) getOriginalMetadata().withTarget(target);
        updateMetadata(metadata);
        return Cast.uncheckedCast(this);
    }

    public void forced() {
        ModuleDependencyMetadata originalMetadata = getOriginalMetadata();
        if (originalMetadata instanceof ForcingDependencyMetadata) {
            updateMetadata((ModuleDependencyMetadata) ((ForcingDependencyMetadata) originalMetadata).forced());
        }
    }

}
