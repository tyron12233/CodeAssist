/*
 * Copyright 2018 the original author or authors.
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

import com.google.common.collect.Sets;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.attributes.Category;
import com.tyron.builder.api.attributes.LibraryElements;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.capabilities.Capability;
import com.tyron.builder.api.component.AdhocComponentWithVariants;
import com.tyron.builder.api.component.SoftwareComponentFactory;
import com.tyron.builder.api.internal.artifacts.JavaEcosystemSupport;
import com.tyron.builder.api.internal.artifacts.dsl.ComponentMetadataHandlerInternal;
import com.tyron.builder.api.internal.java.DefaultJavaPlatformExtension;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.plugins.internal.JavaConfigurationVariantMapping;
import com.tyron.builder.internal.component.external.model.DefaultShadowedCapability;
import com.tyron.builder.internal.component.external.model.JavaEcosystemVariantDerivationStrategy;
import com.tyron.builder.internal.component.external.model.ProjectDerivedCapability;

import javax.inject.Inject;
import java.util.Set;

/**
 * The Java platform plugin allows building platform components
 * for Java, which are usually published as BOM files (for Maven)
 * or Gradle platforms (Gradle metadata).
 *
 * @since 5.2
 * @see <a href="https://docs.gradle.org/current/userguide/java_platform_plugin.html">Java Platform plugin reference</a>
 */
public class JavaPlatformPlugin implements Plugin<BuildProject> {
    // Buckets of dependencies
    public static final String API_CONFIGURATION_NAME = "api";
    public static final String RUNTIME_CONFIGURATION_NAME = "runtime";

    // Consumable configurations
    public static final String API_ELEMENTS_CONFIGURATION_NAME = "apiElements";
    public static final String RUNTIME_ELEMENTS_CONFIGURATION_NAME = "runtimeElements";
    public static final String ENFORCED_API_ELEMENTS_CONFIGURATION_NAME = "enforcedApiElements";
    public static final String ENFORCED_RUNTIME_ELEMENTS_CONFIGURATION_NAME = "enforcedRuntimeElements";

    // Resolvable configurations
    public static final String CLASSPATH_CONFIGURATION_NAME = "classpath";

    private static final Action<Configuration> AS_CONSUMABLE_CONFIGURATION = conf -> {
        conf.setCanBeResolved(false);
        conf.setCanBeConsumed(true);
    };

    private static final Action<Configuration> AS_BUCKET = conf -> {
        conf.setCanBeResolved(false);
        conf.setCanBeConsumed(false);
    };

    private static final Action<Configuration> AS_RESOLVABLE_CONFIGURATION = conf -> {
        conf.setCanBeResolved(true);
        conf.setCanBeConsumed(false);
    };
    private static final String DISALLOW_DEPENDENCIES = "Adding dependencies to platforms is not allowed by default.\n" +
        "Most likely you want to add constraints instead.\n" +
        "If you did this intentionally, you need to configure the platform extension to allow dependencies:\n    javaPlatform.allowDependencies()\n" +
        "Found dependencies in the '%s' configuration.";

    private final SoftwareComponentFactory softwareComponentFactory;

    @Inject
    public JavaPlatformPlugin(SoftwareComponentFactory softwareComponentFactory) {
        this.softwareComponentFactory = softwareComponentFactory;
    }

    @Override
    public void apply(BuildProject project) {
        if (project.getPluginManager().hasPlugin("java")) {
            // This already throws when creating `apiElements` so be eager to have a clear error message
            throw new IllegalStateException(
                "The \"java-platform\" plugin cannot be applied together with the \"java\" (or \"java-library\") plugin. " +
                    "A project is either a platform or a library but cannot be both at the same time."
            );
        }
        project.getPluginManager().apply(BasePlugin.class);
        createConfigurations(project);
        configureExtension(project);
        JavaEcosystemSupport.configureSchema(project.getDependencies().getAttributesSchema(), project.getObjects());
        configureVariantDerivationStrategy((ProjectInternal) project);
    }

    private static void configureVariantDerivationStrategy(ProjectInternal project) {
        ComponentMetadataHandlerInternal metadataHandler = (ComponentMetadataHandlerInternal) project.getDependencies().getComponents();
        metadataHandler.setVariantDerivationStrategy(JavaEcosystemVariantDerivationStrategy.getInstance());
    }

    private void createSoftwareComponent(BuildProject project, Configuration apiElements, Configuration runtimeElements) {
        AdhocComponentWithVariants component = softwareComponentFactory.adhoc("javaPlatform");
        project.getComponents().add(component);
        component.addVariantsFromConfiguration(apiElements, new JavaConfigurationVariantMapping("compile", false));
        component.addVariantsFromConfiguration(runtimeElements, new JavaConfigurationVariantMapping("runtime", false));
    }

    private void createConfigurations(BuildProject project) {
        ConfigurationContainer configurations = project.getConfigurations();
        Capability enforcedCapability = new DefaultShadowedCapability(new ProjectDerivedCapability(project), "-derived-enforced-platform");

        Configuration api = configurations.create(API_CONFIGURATION_NAME, AS_BUCKET);
        Configuration apiElements = createConsumableApi(project, configurations, api, API_ELEMENTS_CONFIGURATION_NAME, Category.REGULAR_PLATFORM);
        Configuration enforcedApiElements = createConsumableApi(project, configurations, api, ENFORCED_API_ELEMENTS_CONFIGURATION_NAME, Category.ENFORCED_PLATFORM);
        enforcedApiElements.getOutgoing().capability(enforcedCapability);

        Configuration runtime = project.getConfigurations().create(RUNTIME_CONFIGURATION_NAME, AS_BUCKET);
        runtime.extendsFrom(api);

        Configuration runtimeElements = createConsumableRuntime(project, runtime, RUNTIME_ELEMENTS_CONFIGURATION_NAME, Category.REGULAR_PLATFORM);
        Configuration enforcedRuntimeElements = createConsumableRuntime(project, runtime, ENFORCED_RUNTIME_ELEMENTS_CONFIGURATION_NAME, Category.ENFORCED_PLATFORM);
        enforcedRuntimeElements.getOutgoing().capability(enforcedCapability);

        Configuration classpath = configurations.create(CLASSPATH_CONFIGURATION_NAME, AS_RESOLVABLE_CONFIGURATION);
        classpath.extendsFrom(runtimeElements);
        declareConfigurationUsage(project.getObjects(), classpath, Usage.JAVA_RUNTIME, LibraryElements.JAR);

        createSoftwareComponent(project, apiElements, runtimeElements);
    }

    private Configuration createConsumableRuntime(BuildProject project, Configuration apiElements, String name, String platformKind) {
        Configuration runtimeElements = project.getConfigurations().create(name, AS_CONSUMABLE_CONFIGURATION);
        runtimeElements.extendsFrom(apiElements);
        declareConfigurationUsage(project.getObjects(), runtimeElements, Usage.JAVA_RUNTIME);
        declareConfigurationCategory(project.getObjects(), runtimeElements, platformKind);
        return runtimeElements;
    }

    private Configuration createConsumableApi(BuildProject project, ConfigurationContainer configurations, Configuration api, String name, String platformKind) {
        Configuration apiElements = configurations.create(name, AS_CONSUMABLE_CONFIGURATION);
        apiElements.extendsFrom(api);
        declareConfigurationUsage(project.getObjects(), apiElements, Usage.JAVA_API);
        declareConfigurationCategory(project.getObjects(), apiElements, platformKind);
        return apiElements;
    }

    private void declareConfigurationCategory(ObjectFactory objectFactory, Configuration configuration, String value) {
        configuration.getAttributes().attribute(Category.CATEGORY_ATTRIBUTE, objectFactory.named(Category.class, value));
    }

    private void declareConfigurationUsage(ObjectFactory objectFactory, Configuration configuration, String usage, String libraryContents) {
        declareConfigurationUsage(objectFactory, configuration, usage);
        configuration.getAttributes().attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objectFactory.named(LibraryElements.class, libraryContents));
    }

    private void declareConfigurationUsage(ObjectFactory objectFactory, Configuration configuration, String usage) {
        configuration.getAttributes().attribute(Usage.USAGE_ATTRIBUTE, objectFactory.named(Usage.class, usage));
    }

    private void configureExtension(BuildProject project) {
        final DefaultJavaPlatformExtension platformExtension = (DefaultJavaPlatformExtension) project.getExtensions().create(JavaPlatformExtension.class, "javaPlatform", DefaultJavaPlatformExtension.class);
        project.afterEvaluate(project1 -> {
            if (!platformExtension.isAllowDependencies()) {
                checkNoDependencies(project1);
            }
        });
    }

    private void checkNoDependencies(BuildProject project) {
        checkNoDependencies(project.getConfigurations().getByName(RUNTIME_CONFIGURATION_NAME), Sets.<Configuration>newHashSet());
    }

    private void checkNoDependencies(Configuration configuration, Set<Configuration> visited) {
        if (visited.add(configuration)) {
            if (!configuration.getDependencies().isEmpty()) {
                throw new InvalidUserCodeException(String.format(DISALLOW_DEPENDENCIES, configuration.getName()));
            }
            Set<Configuration> extendsFrom = configuration.getExtendsFrom();
            for (Configuration parent : extendsFrom) {
                checkNoDependencies(parent, visited);
            }
        }
    }

}
