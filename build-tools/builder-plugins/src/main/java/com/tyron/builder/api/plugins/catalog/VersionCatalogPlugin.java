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
package com.tyron.builder.api.plugins.catalog;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Incubating;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.attributes.Category;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.component.AdhocComponentWithVariants;
import com.tyron.builder.api.component.SoftwareComponentFactory;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.api.plugins.BasePlugin;
import com.tyron.builder.api.plugins.catalog.internal.CatalogExtensionInternal;
import com.tyron.builder.api.plugins.catalog.internal.DefaultVersionCatalogPluginExtension;
import com.tyron.builder.api.plugins.catalog.internal.TomlFileGenerator;
import com.tyron.builder.api.plugins.internal.JavaConfigurationVariantMapping;
import com.tyron.builder.api.tasks.TaskProvider;

import javax.inject.Inject;

/**
 * <p>A {@link Plugin} makes it possible to generate a version catalog,  which is a set of
 * versions and
 * coordinates for dependencies and plugins to import in the settings of a Gradle build.</p>
 *
 * @since 7.0
 */
@Incubating
public class VersionCatalogPlugin implements Plugin<BuildProject> {
    private final static Logger LOGGER = Logging.getLogger(VersionCatalogPlugin.class);

    public static final String GENERATE_CATALOG_FILE_TASKNAME = "generateCatalogAsToml";
    public static final String GRADLE_PLATFORM_DEPENDENCIES = "versionCatalog";
    public static final String VERSION_CATALOG_ELEMENTS = "versionCatalogElements";

    private final SoftwareComponentFactory softwareComponentFactory;

    @Inject
    public VersionCatalogPlugin(SoftwareComponentFactory softwareComponentFactory) {
        this.softwareComponentFactory = softwareComponentFactory;
    }

    @Override
    public void apply(BuildProject project) {
        Configuration dependenciesConfiguration = createDependenciesConfiguration(project);
        CatalogExtensionInternal extension = createExtension(project, dependenciesConfiguration);
        TaskProvider<TomlFileGenerator> generator = createGenerator(project, extension);
        createPublication(project, generator);
    }

    private void createPublication(BuildProject project,
                                   TaskProvider<TomlFileGenerator> generator) {
        Configuration exported =
                project.getConfigurations().create(VERSION_CATALOG_ELEMENTS, cnf -> {
                    cnf.setDescription("Artifacts for the version catalog");
                    cnf.setCanBeConsumed(true);
                    cnf.setCanBeResolved(false);
                    cnf.getOutgoing().artifact(generator);
                    cnf.attributes(attrs -> {
                        attrs.attribute(Category.CATEGORY_ATTRIBUTE, project.getObjects()
                                .named(Category.class, Category.REGULAR_PLATFORM));
                        attrs.attribute(Usage.USAGE_ATTRIBUTE,
                                project.getObjects().named(Usage.class, Usage.VERSION_CATALOG));
                    });
                });
        AdhocComponentWithVariants versionCatalog =
                softwareComponentFactory.adhoc("versionCatalog");
        project.getComponents().add(versionCatalog);
        versionCatalog.addVariantsFromConfiguration(exported,
                new JavaConfigurationVariantMapping("compile", true));
    }

    private Configuration createDependenciesConfiguration(BuildProject project) {
        return project.getConfigurations().create(GRADLE_PLATFORM_DEPENDENCIES, cnf -> {
            cnf.setVisible(false);
            cnf.setCanBeConsumed(false);
            cnf.setCanBeResolved(false);
        });
    }

    private TaskProvider<TomlFileGenerator> createGenerator(BuildProject project,
                                                            CatalogExtensionInternal extension) {
        return project.getTasks().register(GENERATE_CATALOG_FILE_TASKNAME, TomlFileGenerator.class,
                t -> configureTask(project, extension, t));
    }

    private void configureTask(BuildProject project,
                               CatalogExtensionInternal extension,
                               TomlFileGenerator task) {
        task.setGroup(BasePlugin.BUILD_GROUP);
        task.setDescription("Generates a TOML file for a version catalog");
        task.getOutputFile().convention(
                project.getLayout().getBuildDirectory().file("version-catalog/libs.versions.toml"));
        task.getDependenciesModel().convention(extension.getVersionCatalog());
    }

    private CatalogExtensionInternal createExtension(BuildProject project,
                                                     Configuration dependenciesConfiguration) {
        return (CatalogExtensionInternal) project.getExtensions()
                .create(CatalogPluginExtension.class, "catalog",
                        DefaultVersionCatalogPluginExtension.class, dependenciesConfiguration);
    }

}
