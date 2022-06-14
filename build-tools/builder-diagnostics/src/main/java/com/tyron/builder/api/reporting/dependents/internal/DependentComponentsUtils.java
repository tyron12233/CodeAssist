/*
 * Copyright 2016 the original author or authors.
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

package com.tyron.builder.api.reporting.dependents.internal;

import com.google.common.collect.Sets;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.component.LibraryBinaryIdentifier;
import com.tyron.builder.model.ModelMap;
import com.tyron.builder.model.internal.registry.ModelRegistry;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.platform.base.ComponentSpec;
import com.tyron.builder.platform.base.ComponentSpecContainer;
import com.tyron.builder.platform.base.internal.ComponentSpecIdentifier;

import javax.annotation.Nullable;
import java.util.Set;

import static com.tyron.builder.model.internal.type.ModelTypes.modelMap;

public class DependentComponentsUtils {

    public static String getBuildScopedTerseName(ComponentSpecIdentifier id) {
        return getProjectPrefix(id.getProjectPath()) + id.getProjectScopedName();
    }

    public static String getBuildScopedTerseName(LibraryBinaryIdentifier id) {
        return getProjectPrefix(id.getProjectPath()) + id.getLibraryName() + BuildProject.PATH_SEPARATOR + id.getVariant();
    }

    private static String getProjectPrefix(String projectPath) {
        if (BuildProject.PATH_SEPARATOR.equals(projectPath)) {
            return "";
        }
        return projectPath + BuildProject.PATH_SEPARATOR;
    }


    public static Set<ComponentSpec> getAllComponents(ModelRegistry registry) {
        Set<ComponentSpec> components = Sets.newLinkedHashSet();
        ComponentSpecContainer componentSpecs = modelElement(registry, "components", ComponentSpecContainer.class);
        if (componentSpecs != null) {
            components.addAll(componentSpecs.values());
        }
        return components;
    }

    public static Set<ComponentSpec> getAllTestSuites(ModelRegistry registry) {
        Set<ComponentSpec> components = Sets.newLinkedHashSet();
        ModelMap<ComponentSpec> testSuites = modelElement(registry, "testSuites", modelMap(ComponentSpec.class));
        if (testSuites != null) {
            components.addAll(testSuites.values());
        }
        return components;
    }

    @Nullable
    private static <T> T modelElement(ModelRegistry registry, String path, Class<T> clazz) {
        return registry.find(path, clazz);
    }

    @Nullable
    private static <T> T modelElement(ModelRegistry registry, String path, ModelType<T> modelType) {
        return registry.find(path, modelType);
    }

}
