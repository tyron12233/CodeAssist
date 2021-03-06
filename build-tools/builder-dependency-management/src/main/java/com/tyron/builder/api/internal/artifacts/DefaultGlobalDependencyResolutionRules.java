/*
 * Copyright 2014 the original author or authors.
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

package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionRules;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.artifacts.DependencySubstitution;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.util.internal.CollectionUtils;

import java.util.List;

public class DefaultGlobalDependencyResolutionRules implements GlobalDependencyResolutionRules {
    private final ComponentMetadataProcessorFactory componentMetadataProcessorFactory;
    private final ComponentModuleMetadataProcessor moduleMetadataProcessor;
    private final DependencySubstitutionRules globalDependencySubstitutionRule;

    public DefaultGlobalDependencyResolutionRules(ComponentMetadataProcessorFactory componentMetadataProcessorFactory,
                                                  ComponentModuleMetadataProcessor moduleMetadataProcessor,
                                                  List<DependencySubstitutionRules> ruleProviders) {
        this.componentMetadataProcessorFactory = componentMetadataProcessorFactory;
        this.moduleMetadataProcessor = moduleMetadataProcessor;
        this.globalDependencySubstitutionRule = new CompositeDependencySubstitutionRules(ruleProviders);
    }

    @Override
    public ComponentMetadataProcessorFactory getComponentMetadataProcessorFactory() {
        return componentMetadataProcessorFactory;
    }

    @Override
    public ComponentModuleMetadataProcessor getModuleMetadataProcessor() {
        return moduleMetadataProcessor;
    }

    @Override
    public DependencySubstitutionRules getDependencySubstitutionRules() {
        return globalDependencySubstitutionRule;
    }

    private static class CompositeDependencySubstitutionRules implements DependencySubstitutionRules {
        private final List<DependencySubstitutionRules> ruleProviders;

        private CompositeDependencySubstitutionRules(List<DependencySubstitutionRules> ruleProviders) {
            this.ruleProviders = ruleProviders;
        }

        @Override
        public Action<DependencySubstitution> getRuleAction() {
            return Actions.composite(CollectionUtils.collect(ruleProviders, DependencySubstitutionRules::getRuleAction));
        }

        @Override
        public boolean rulesMayAddProjectDependency() {
            for (DependencySubstitutionRules ruleProvider : ruleProviders) {
                if (ruleProvider.rulesMayAddProjectDependency()) {
                    return true;
                }
            }
            return false;
        }
    }
}
