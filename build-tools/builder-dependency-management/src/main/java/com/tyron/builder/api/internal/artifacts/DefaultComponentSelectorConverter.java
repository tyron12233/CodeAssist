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
package com.tyron.builder.api.internal.artifacts;

import com.tyron.builder.api.internal.artifacts.component.ComponentIdentifierFactory;
import com.tyron.builder.api.internal.artifacts.ivyservice.projectmodule.LocalComponentRegistry;

import com.tyron.builder.api.artifacts.ModuleIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.ModuleVersionSelector;
import com.tyron.builder.api.artifacts.component.ComponentSelector;
import com.tyron.builder.api.artifacts.component.LibraryComponentSelector;
import com.tyron.builder.api.artifacts.component.ModuleComponentSelector;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentSelector;
import com.tyron.builder.internal.component.local.model.LocalComponentMetadata;
import com.tyron.builder.util.internal.GUtil;

public class DefaultComponentSelectorConverter implements ComponentSelectorConverter {
    private static final ModuleVersionSelector UNKNOWN_MODULE_VERSION_SELECTOR = DefaultModuleVersionSelector.newSelector(DefaultModuleIdentifier.newId("", "unknown"), "");
    private final ComponentIdentifierFactory componentIdentifierFactory;
    private final LocalComponentRegistry localComponentRegistry;

    public DefaultComponentSelectorConverter(ComponentIdentifierFactory componentIdentifierFactory, LocalComponentRegistry localComponentRegistry) {
        this.componentIdentifierFactory = componentIdentifierFactory;
        this.localComponentRegistry = localComponentRegistry;
    }

    @Override
    public ModuleIdentifier getModule(ComponentSelector componentSelector) {
        if (componentSelector instanceof ModuleComponentSelector) {
            ModuleComponentSelector module = (ModuleComponentSelector) componentSelector;
            return module.getModuleIdentifier();
        }
        ModuleVersionSelector moduleVersionSelector = getSelector(componentSelector);
        return moduleVersionSelector.getModule();
    }

    @Override
    public ModuleVersionSelector getSelector(ComponentSelector selector) {
        if (selector instanceof ModuleComponentSelector) {
            return DefaultModuleVersionSelector.newSelector((ModuleComponentSelector) selector);
        }
        if (selector instanceof ProjectComponentSelector) {
            ProjectComponentSelector projectSelector = (ProjectComponentSelector) selector;
            ProjectComponentIdentifier projectId = componentIdentifierFactory.createProjectComponentIdentifier(projectSelector);
            LocalComponentMetadata projectComponent = localComponentRegistry.getComponent(projectId);
            if (projectComponent != null) {
                ModuleVersionIdentifier moduleVersionId = projectComponent.getModuleVersionId();
                return DefaultModuleVersionSelector.newSelector(moduleVersionId.getModule(), moduleVersionId.getVersion());
            }
        }
        if (selector instanceof LibraryComponentSelector) {
            LibraryComponentSelector libraryComponentSelector = (LibraryComponentSelector) selector;
            String libraryName = GUtil.elvis(libraryComponentSelector.getLibraryName(), "");
            return DefaultModuleVersionSelector.newSelector(DefaultModuleIdentifier.newId(libraryComponentSelector.getProjectPath(), libraryName), "undefined");
        }
        return UNKNOWN_MODULE_VERSION_SELECTOR;
    }
}
