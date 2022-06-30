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

package com.tyron.builder.api.reporting.components;

import com.tyron.builder.api.DefaultTask;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.reporting.components.internal.ComponentReportRenderer;
import com.tyron.builder.api.reporting.components.internal.TypeAwareBinaryRenderer;
import com.tyron.builder.api.tasks.TaskAction;
import com.tyron.builder.api.tasks.diagnostics.internal.ProjectDetails;
import com.tyron.builder.internal.logging.text.StyledTextOutput;
import com.tyron.builder.internal.logging.text.StyledTextOutputFactory;
import com.tyron.builder.language.base.ProjectSourceSet;
import com.tyron.builder.model.ModelMap;
import com.tyron.builder.model.internal.registry.ModelRegistry;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.platform.base.BinaryContainer;
import com.tyron.builder.platform.base.ComponentSpec;
import com.tyron.builder.platform.base.ComponentSpecContainer;
import com.tyron.builder.work.DisableCachingByDefault;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;

import static com.tyron.builder.model.internal.type.ModelTypes.modelMap;

/**
 * Displays some details about the software components produced by the project.
 */
@Deprecated
@DisableCachingByDefault(because = "Produces only non-cacheable console output")
public class ComponentReport extends DefaultTask {
    @Inject
    protected StyledTextOutputFactory getTextOutputFactory() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected FileResolver getFileResolver() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected ModelRegistry getModelRegistry() {
        throw new UnsupportedOperationException();
    }

    @Inject
    protected TypeAwareBinaryRenderer getBinaryRenderer() {
        throw new UnsupportedOperationException();
    }

    @TaskAction
    public void report() {
        ProjectInternal project = (ProjectInternal) getProject();
        project.prepareForRuleBasedPlugins();

        StyledTextOutput textOutput = getTextOutputFactory().create(ComponentReport.class);
        ComponentReportRenderer renderer = new ComponentReportRenderer(getFileResolver(), getBinaryRenderer());
        renderer.setOutput(textOutput);

        ProjectDetails projectDetails = ProjectDetails.of(project);
        renderer.startProject(projectDetails);

        Collection<ComponentSpec> components = new ArrayList<>();
        ComponentSpecContainer componentSpecs = modelElement("components", ComponentSpecContainer.class);
        if (componentSpecs != null) {
            components.addAll(componentSpecs.values());
        }

        ModelMap<ComponentSpec> testSuites = modelElement("testSuites", modelMap(ComponentSpec.class));
        if (testSuites != null) {
            components.addAll(testSuites.values());
        }

        renderer.renderComponents(components);

        ProjectSourceSet sourceSets = modelElement("sources", ProjectSourceSet.class);
        if (sourceSets != null) {
            renderer.renderSourceSets(sourceSets);
        }
        BinaryContainer binaries = modelElement("binaries", BinaryContainer.class);
        if (binaries != null) {
            renderer.renderBinaries(binaries.values());
        }

        renderer.completeProject(projectDetails);
        renderer.complete();
    }

    private <T> T modelElement(String path, Class<T> clazz) {
        return getModelRegistry().find(path, clazz);
    }

    private <T> T modelElement(String path, ModelType<T> modelType) {
        return getModelRegistry().find(path, modelType);
    }
}
