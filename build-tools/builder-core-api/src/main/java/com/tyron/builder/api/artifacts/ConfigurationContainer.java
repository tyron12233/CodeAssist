package com.tyron.builder.api.artifacts;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.internal.HasInternalProtocol;

/**
 * <p>A {@code ConfigurationContainer} is responsible for declaring and managing configurations. See also {@link Configuration}.</p>
 *
 * <p>You can obtain a {@code ConfigurationContainer} instance by calling {@link com.tyron.builder.api.Project#getConfigurations()},
 * or using the {@code configurations} property in your build script.</p>
 *
 * <p>The configurations in a container are accessible as read-only properties of the container, using the name of the
 * configuration as the property name. For example:</p>
 *
 * <pre class='autoTested'>
 * configurations.create('myConfiguration')
 * configurations.myConfiguration.transitive = false
 * </pre>
 *
 * <p>A dynamic method is added for each configuration which takes a configuration closure. This is equivalent to
 * calling {@link #getByName(String, groovy.lang.Closure)}. For example:</p>
 *
 * <pre class='autoTested'>
 * configurations.create('myConfiguration')
 * configurations.myConfiguration {
 *     transitive = false
 * }
 * </pre>
 *
 * <h2>Examples</h2>
 *
 * An example showing how to refer to a given configuration by name
 * in order to get hold of all dependencies (e.g. jars, but only)
 * <pre class='autoTested'>
 *   plugins {
 *       id 'java' //so that I can use 'implementation', 'compileClasspath' configuration
 *   }
 *
 *   dependencies {
 *       implementation 'org.slf4j:slf4j-api:1.7.26'
 *   }
 *
 *   //copying all dependencies attached to 'compileClasspath' into a specific folder
 *   task copyAllDependencies(type: Copy) {
 *     //referring to the 'compileClasspath' configuration
 *     from configurations.compileClasspath
 *     into 'allLibs'
 *   }
 * </pre>
 *
 * An example showing how to declare and configure configurations
 * <pre class='autoTested'>
 * plugins {
 *     id 'java' // so that I can use 'implementation', 'testImplementation' configurations
 * }
 *
 * configurations {
 *   //adding a configuration:
 *   myConfiguration
 *
 *   //adding a configuration that extends existing configuration:
 *   //(testImplementation was added by the java plugin)
 *   myIntegrationTestsCompile.extendsFrom(testImplementation)
 *
 *   //configuring existing configurations not to put transitive dependencies on the compile classpath
 *   //this way you can avoid issues with implicit dependencies to transitive libraries
 *   compileClasspath.transitive = false
 *   testCompileClasspath.transitive = false
 * }
 * </pre>
 *
 * Examples on configuring the <b>resolution strategy</b> - see docs for {@link ResolutionStrategy}
 *
 * Please see the <a href="https://docs.gradle.org/current/userguide/declaring_dependencies.html#sec:what-are-dependency-configurations" target="_top">Managing Dependency Configurations</a> User Manual chapter for more information.
 */
@HasInternalProtocol
public interface ConfigurationContainer extends NamedDomainObjectContainer<Configuration> {
    /**
     * {@inheritDoc}
     */
    @Override
    Configuration getByName(String name) throws UnknownConfigurationException;

    /**
     * {@inheritDoc}
     */
    @Override
    Configuration getAt(String name) throws UnknownConfigurationException;

    /**
     * {@inheritDoc}
     */
    @Override
    Configuration getByName(String name, Closure configureClosure) throws UnknownConfigurationException;

    /**
     * {@inheritDoc}
     */
    @Override
    Configuration getByName(String name, Action<? super Configuration> configureAction) throws UnknownConfigurationException;

    /**
     * Creates a configuration, but does not add it to this container.
     *
     * @param dependencies The dependencies of the configuration.
     * @return The configuration.
     */
    Configuration detachedConfiguration(Dependency... dependencies);
}
