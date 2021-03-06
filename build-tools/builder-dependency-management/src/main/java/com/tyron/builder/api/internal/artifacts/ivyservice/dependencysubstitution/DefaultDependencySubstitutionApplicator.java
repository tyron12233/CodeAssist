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
package com.tyron.builder.api.internal.artifacts.ivyservice.dependencysubstitution;

import com.tyron.builder.api.internal.artifacts.ivyservice.resolveengine.result.ComponentSelectionDescriptorFactory;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencySubstitution;
import com.tyron.builder.api.internal.artifacts.DependencySubstitutionInternal;
import com.tyron.builder.internal.component.model.DependencyMetadata;
import com.tyron.builder.internal.reflect.Instantiator;

public class DefaultDependencySubstitutionApplicator implements DependencySubstitutionApplicator {
    private final ComponentSelectionDescriptorFactory componentSelectionDescriptorFactory;
    private final Action<DependencySubstitution> rule;
    private final Instantiator instantiator;

    public DefaultDependencySubstitutionApplicator(ComponentSelectionDescriptorFactory componentSelectionDescriptorFactory, Action<DependencySubstitution> rule, Instantiator instantiator) {
        this.componentSelectionDescriptorFactory = componentSelectionDescriptorFactory;
        this.rule = rule;
        this.instantiator = instantiator;
    }

    @Override
    public SubstitutionResult apply(DependencyMetadata dependency) {
        DependencySubstitutionInternal details = instantiator.newInstance(DefaultDependencySubstitution.class,
            componentSelectionDescriptorFactory,
            dependency.getSelector(),
            dependency.getArtifacts());
        try {
            rule.execute(details);
        } catch (Exception e) {
            return SubstitutionResult.failed(e);
        }
        return SubstitutionResult.of(details);
    }

}
