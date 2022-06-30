/*
 * Copyright 2020 the original author or authors.
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
package com.tyron.builder.api.plugins;

import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.type.ArtifactTypeDefinition;
import com.tyron.builder.api.attributes.AttributesSchema;
import com.tyron.builder.api.attributes.LibraryElements;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.internal.artifacts.JavaEcosystemSupport;
import com.tyron.builder.api.internal.artifacts.dsl.ComponentMetadataHandlerInternal;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.plugins.jvm.internal.JvmPluginServices;
import com.tyron.builder.api.tasks.SourceSetContainer;
import com.tyron.builder.internal.component.external.model.JavaEcosystemVariantDerivationStrategy;

import javax.inject.Inject;

/**
 * A base plugin for projects working in a JVM world. This plugin
 * will configure the JVM attributes schema, setup resolution rules
 * and create the source set container.
 *
 * @since 6.7
 * @see <a href="https://docs.gradle.org/current/userguide/java_plugin.html">Java plugin reference</a>
 */
public class JvmEcosystemPlugin implements Plugin<BuildProject> {
    private final ObjectFactory objectFactory;
    private final JvmPluginServices jvmPluginServices;
    private final SourceSetContainer sourceSets;

    @Inject
    public JvmEcosystemPlugin(ObjectFactory objectFactory, JvmPluginServices jvmPluginServices, SourceSetContainer sourceSets) {
        this.objectFactory = objectFactory;
        this.jvmPluginServices = jvmPluginServices;
        this.sourceSets = sourceSets;
    }

    @Override
    public void apply(BuildProject project) {
        ProjectInternal p = (ProjectInternal) project;
        project.getExtensions().add(SourceSetContainer.class, "sourceSets", sourceSets);
        configureVariantDerivationStrategy(p);
        configureSchema(p);
        jvmPluginServices.inject(p, sourceSets);
    }

    private void configureVariantDerivationStrategy(ProjectInternal project) {
        ComponentMetadataHandlerInternal metadataHandler = (ComponentMetadataHandlerInternal) project.getDependencies().getComponents();
        metadataHandler.setVariantDerivationStrategy(JavaEcosystemVariantDerivationStrategy.getInstance());
    }


    private void configureSchema(ProjectInternal project) {
        AttributesSchema attributesSchema = project.getDependencies().getAttributesSchema();
        JavaEcosystemSupport.configureSchema(attributesSchema, objectFactory);
        project.getDependencies().getArtifactTypes().create(ArtifactTypeDefinition.JAR_TYPE).getAttributes()
            .attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, Usage.JAVA_RUNTIME))
            .attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objectFactory.named(LibraryElements.class, LibraryElements.JAR));
    }

}
