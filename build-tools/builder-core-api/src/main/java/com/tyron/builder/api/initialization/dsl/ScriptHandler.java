package com.tyron.builder.api.initialization.dsl;

import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.Script;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.dsl.DependencyHandler;
import com.tyron.builder.api.artifacts.dsl.DependencyLockingHandler;
import com.tyron.builder.api.artifacts.dsl.RepositoryHandler;

import javax.annotation.Nullable;
import java.io.File;
import java.net.URI;

import groovy.lang.Closure;

/**
 * <p>A {@code ScriptHandler} allows you to manage the compilation and execution of a build script. You can declare the
 * classpath used to compile and execute a build script. This classpath is also used to load the plugins which the build
 * script uses.</p>
 *
 * <p>You can obtain a {@code ScriptHandler} instance using {@link BuildProject#getBuildscript()} or {@link
 * Script#getBuildscript()}.</p>
 *
 * <p>To declare the script classpath, you use the {@link com.tyron.builder.api.artifacts.dsl.DependencyHandler} provided by
 * {@link #getDependencies()} to attach dependencies to the {@value #CLASSPATH_CONFIGURATION} configuration. These
 * dependencies are resolved just prior to script compilation, and assembled into the classpath for the script.</p>
 *
 * <p>For most external dependencies you will also need to declare one or more repositories where the dependencies can
 * be found, using the {@link com.tyron.builder.api.artifacts.dsl.RepositoryHandler} provided by {@link
 * #getRepositories()}.</p>
 */
public interface ScriptHandler {
    /**
     * The name of the configuration used to assemble the script classpath.
     */
    String CLASSPATH_CONFIGURATION = "classpath";

    /**
     * Returns the file containing the source for the script, if any.
     *
     * @return The source file. Returns null if the script source is not a file.
     */
    @Nullable
    File getSourceFile();

    /**
     * Returns the URI for the script source, if any.
     *
     * @return The source URI. Returns null if the script source has no URI.
     */
    @Nullable
    URI getSourceURI();

    /**
     * Returns a handler to create repositories which are used for retrieving dependencies for the script classpath.
     *
     * @return the repository handler. Never returns null.
     */
    RepositoryHandler getRepositories();

    /**
     * Configures the repositories for the script dependencies. Executes the given closure against the {@link
     * RepositoryHandler} for this handler. The {@link RepositoryHandler} is passed to the closure as the closure's
     * delegate.
     *
     * @param configureClosure the closure to use to configure the repositories.
     */
    void repositories(Closure configureClosure);

    /**
     * Returns the dependencies of the script. The returned dependency handler instance can be used for adding new
     * dependencies. For accessing already declared dependencies, the configurations can be used.
     *
     * @return the dependency handler. Never returns null.
     * @see #getConfigurations()
     */
    DependencyHandler getDependencies();

    /**
     * Configures the dependencies for the script. Executes the given closure against the {@link DependencyHandler} for
     * this handler. The {@link DependencyHandler} is passed to the closure as the closure's delegate.
     *
     * @param configureClosure the closure to use to configure the dependencies.
     */
    void dependencies(Closure configureClosure);

    /**
     * Returns the configurations of this handler. This usually contains a single configuration, called {@value
     * #CLASSPATH_CONFIGURATION}.
     *
     * @return The configuration of this handler.
     */
    ConfigurationContainer getConfigurations();

    /**
     * Configures dependency locking
     *
     * @param configureClosure the configuration action
     * @since 6.1
     */
    void dependencyLocking(Closure configureClosure);

    /**
     * Provides access to configuring dependency locking
     *
     * @return the {@link DependencyLockingHandler}
     *
     * @since 6.1
     */
    DependencyLockingHandler getDependencyLocking();

    /**
     * Returns the {@code ClassLoader} which contains the classpath for this script.
     *
     * @return The ClassLoader. Never returns null.
     */
    ClassLoader getClassLoader();
}
