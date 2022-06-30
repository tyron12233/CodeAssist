/*
 * Copyright 2019 the original author or authors.
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

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.ProjectDependency;
import com.tyron.builder.api.artifacts.dsl.DependencyHandler;
import com.tyron.builder.api.plugins.jvm.internal.JvmModelingServices;
import com.tyron.builder.api.tasks.SourceSet;
import com.tyron.builder.internal.component.external.model.ProjectTestFixtures;

import javax.inject.Inject;

import static com.tyron.builder.api.plugins.JavaPlugin.TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME;
import static com.tyron.builder.api.plugins.JavaPlugin.TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME;
import static com.tyron.builder.internal.component.external.model.TestFixturesSupport.TEST_FIXTURES_API;
import static com.tyron.builder.internal.component.external.model.TestFixturesSupport.TEST_FIXTURES_FEATURE_NAME;

/**
 * Adds support for producing test fixtures. This plugin will automatically
 * create a `testFixtures` source set, and wires the tests to use those
 * test fixtures automatically.
 *
 * Other projects may consume the test fixtures of the current project by
 * declaring a dependency using the {@link DependencyHandler#testFixtures(Object)}
 * method.
 *
 * @since 5.6
 * @see <a href="https://docs.gradle.org/current/userguide/java_testing.html#sec:java_test_fixtures">Java Test Fixtures reference</a>
 */
public class JavaTestFixturesPlugin implements Plugin<BuildProject> {

    private final JvmModelingServices jvmEcosystemUtilities;

    @Inject
    public JavaTestFixturesPlugin(JvmModelingServices jvmModelingServices) {
        this.jvmEcosystemUtilities = jvmModelingServices;
    }

    @Override
    public void apply(BuildProject project) {
        project.getPluginManager().withPlugin("java", plugin -> {
            jvmEcosystemUtilities.createJvmVariant(TEST_FIXTURES_FEATURE_NAME, builder ->
                builder
                    .exposesApi()
                    .published()
            );
            createImplicitTestFixturesDependencies(project, findJavaExtension(project));
        });
    }

    private void createImplicitTestFixturesDependencies(BuildProject project, JavaPluginExtension extension) {
        DependencyHandler dependencies = project.getDependencies();
        dependencies.add(TEST_FIXTURES_API, dependencies.create(project));
        SourceSet testSourceSet = findTestSourceSet(extension);
        ProjectDependency testDependency = (ProjectDependency) dependencies.add(testSourceSet.getImplementationConfigurationName(), dependencies.create(project));
        testDependency.capabilities(new ProjectTestFixtures(project));

        // Overwrite what the Java plugin defines for test, in order to avoid duplicate classes
        // see gradle/gradle#10872
        ConfigurationContainer configurations = project.getConfigurations();
        testSourceSet.setCompileClasspath(project.getObjects().fileCollection().from(configurations.getByName(TEST_COMPILE_CLASSPATH_CONFIGURATION_NAME)));
        testSourceSet.setRuntimeClasspath(project.getObjects().fileCollection().from(testSourceSet.getOutput(), configurations.getByName(TEST_RUNTIME_CLASSPATH_CONFIGURATION_NAME)));

    }

    private SourceSet findTestSourceSet(JavaPluginExtension extension) {
        return extension.getSourceSets().getByName("test");
    }

    private JavaPluginExtension findJavaExtension(BuildProject project) {
        return project.getExtensions().getByType(JavaPluginExtension.class);
    }

}
