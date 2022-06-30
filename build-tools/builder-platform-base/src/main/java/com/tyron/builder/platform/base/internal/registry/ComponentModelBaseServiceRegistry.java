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

package com.tyron.builder.platform.base.internal.registry;

import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.api.internal.resolve.DefaultProjectModelResolver;
import com.tyron.builder.api.internal.resolve.ProjectModelResolver;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.scopes.AbstractPluginServiceRegistry;
import com.tyron.builder.model.internal.inspect.MethodModelRuleExtractor;
import com.tyron.builder.model.internal.manage.schema.ModelSchemaStore;
import com.tyron.builder.model.internal.manage.schema.extract.ModelSchemaAspectExtractionStrategy;
import com.tyron.builder.platform.base.internal.VariantAspectExtractionStrategy;

public class ComponentModelBaseServiceRegistry extends AbstractPluginServiceRegistry {

    @Override
    public void registerGlobalServices(ServiceRegistration registration) {
        registration.addProvider(new GlobalScopeServices());
    }

    @Override
    public void registerBuildServices(ServiceRegistration registration) {
        registration.addProvider(new BuildScopeServices());
    }

    private static class BuildScopeServices {
        ProjectModelResolver createProjectLocator(final ProjectRegistry<ProjectInternal> projectRegistry) {
            return new DefaultProjectModelResolver(projectRegistry);
        }
    }

    private static class GlobalScopeServices {
        MethodModelRuleExtractor createComponentModelPluginInspector(ModelSchemaStore schemaStore) {
            return new ComponentTypeModelRuleExtractor(schemaStore);
        }

        MethodModelRuleExtractor createComponentBinariesPluginInspector() {
            return new ComponentBinariesModelRuleExtractor();
        }

        MethodModelRuleExtractor createBinaryTaskPluginInspector() {
            return new BinaryTasksModelRuleExtractor();
        }

        ModelSchemaAspectExtractionStrategy createVariantAspectExtractionStrategy() {
            return new VariantAspectExtractionStrategy();
        }
    }
}
