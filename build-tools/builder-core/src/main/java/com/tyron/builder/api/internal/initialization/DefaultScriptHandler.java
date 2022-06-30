package com.tyron.builder.api.internal.initialization;

import com.tyron.builder.api.JavaVersion;
import com.tyron.builder.api.artifacts.Configuration;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.dsl.DependencyHandler;
import com.tyron.builder.api.artifacts.dsl.DependencyLockingHandler;
import com.tyron.builder.api.artifacts.dsl.RepositoryHandler;
import com.tyron.builder.api.attributes.AttributeContainer;
import com.tyron.builder.api.attributes.Bundling;
import com.tyron.builder.api.attributes.Category;
import com.tyron.builder.api.attributes.LibraryElements;
import com.tyron.builder.api.attributes.Usage;
import com.tyron.builder.api.attributes.java.TargetJvmVersion;
import com.tyron.builder.api.attributes.plugin.GradlePluginApiVersion;
import com.tyron.builder.api.initialization.dsl.ScriptHandler;
import com.tyron.builder.api.internal.DynamicObjectAware;
import com.tyron.builder.api.internal.artifacts.DependencyResolutionServices;
import com.tyron.builder.api.internal.artifacts.JavaEcosystemSupport;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.logging.Logger;
import com.tyron.builder.api.logging.Logging;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.classloader.ClasspathUtil;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.metaobject.BeanDynamicObject;
import com.tyron.builder.internal.metaobject.DynamicObject;
import com.tyron.builder.internal.resource.ResourceLocation;
import com.tyron.builder.util.ConfigureUtil;
import com.tyron.builder.util.GradleVersion;

import java.io.File;
import java.net.URI;

import groovy.lang.Closure;

public class DefaultScriptHandler implements ScriptHandler, ScriptHandlerInternal, DynamicObjectAware {
    private static final Logger LOGGER = Logging.getLogger(DefaultScriptHandler.class);

    private final ResourceLocation scriptResource;
    private final ClassLoaderScope classLoaderScope;
    private final ScriptClassPathResolver scriptClassPathResolver;
    private final DependencyResolutionServices dependencyResolutionServices;
    private final NamedObjectInstantiator instantiator;
    private final DependencyLockingHandler dependencyLockingHandler;
    // The following values are relatively expensive to create, so defer creation until required
    private RepositoryHandler repositoryHandler;
    private DependencyHandler dependencyHandler;
    private ConfigurationContainer configContainer;
    private Configuration classpathConfiguration;
    private DynamicObject dynamicObject;

    public DefaultScriptHandler(
            ScriptSource scriptSource,
            DependencyResolutionServices dependencyResolutionServices,
            ClassLoaderScope classLoaderScope,
            ScriptClassPathResolver scriptClassPathResolver,
            NamedObjectInstantiator instantiator
    ) {
        this.dependencyResolutionServices = dependencyResolutionServices;
        this.scriptResource = scriptSource.getResource().getLocation();
        this.classLoaderScope = classLoaderScope;
        this.scriptClassPathResolver = scriptClassPathResolver;
        this.instantiator = instantiator;
        this.dependencyLockingHandler = dependencyResolutionServices.getDependencyLockingHandler();
        JavaEcosystemSupport.configureSchema(dependencyResolutionServices.getAttributesSchema(), dependencyResolutionServices.getObjectFactory());
    }

    @Override
    public void dependencies(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getDependencies());
    }

    @Override
    public void addScriptClassPathDependency(Object notation) {
        getDependencies().add(ScriptHandler.CLASSPATH_CONFIGURATION, notation);
    }

    @Override
    public ClassPath getScriptClassPath() {
        return ClasspathUtil.getClasspath(getClassLoader());
    }

    @Override
    public ClassPath getNonInstrumentedScriptClassPath() {
        return scriptClassPathResolver.resolveClassPath(classpathConfiguration);
    }

    @Override
    public DependencyHandler getDependencies() {
        defineConfiguration();
        if (dependencyHandler == null) {
            dependencyHandler = dependencyResolutionServices.getDependencyHandler();
        }
        return dependencyHandler;
    }

    @Override
    public RepositoryHandler getRepositories() {
        if (repositoryHandler == null) {
            repositoryHandler = dependencyResolutionServices.getResolveRepositoryHandler();
        }
        return repositoryHandler;
    }

    @Override
    public void repositories(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getRepositories());
    }

    @Override
    public ConfigurationContainer getConfigurations() {
        defineConfiguration();
        return configContainer;
    }

    private void defineConfiguration() {
        // Defer creation and resolution of configuration until required. Short-circuit when script does not require classpath
        if (configContainer == null) {
            configContainer = dependencyResolutionServices.getConfigurationContainer();
        }
        if (classpathConfiguration == null) {
            classpathConfiguration = configContainer.create(CLASSPATH_CONFIGURATION);
            // should ideally reuse the `JvmEcosystemUtilities` but this code is too low level
            // and this service is therefore not available!
            AttributeContainer attributes = classpathConfiguration.getAttributes();
            attributes.attribute(Usage.USAGE_ATTRIBUTE, instantiator.named(Usage.class, Usage.JAVA_RUNTIME));
            attributes.attribute(Category.CATEGORY_ATTRIBUTE, instantiator.named(Category.class, Category.LIBRARY));
            attributes.attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, instantiator.named(LibraryElements.class, LibraryElements.JAR));
            attributes.attribute(Bundling.BUNDLING_ATTRIBUTE, instantiator.named(Bundling.class, Bundling.EXTERNAL));
            attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, Integer.parseInt(JavaVersion.current().getMajorVersion()));
            attributes.attribute(GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE, instantiator.named(GradlePluginApiVersion.class, GradleVersion.current().getVersion()));
        }
    }

    @Override
    public void dependencyLocking(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getDependencyLocking());
    }

    @Override
    public DependencyLockingHandler getDependencyLocking() {
        return dependencyLockingHandler;
    }

    @Override
    public File getSourceFile() {
        return scriptResource.getFile();
    }

    @Override
    public URI getSourceURI() {
        return scriptResource.getURI();
    }

    @Override
    public ClassLoader getClassLoader() {
        if (!classLoaderScope.isLocked()) {
            LOGGER.debug("Eager creation of script class loader for {}. This may result in performance issues.", scriptResource.getDisplayName());
        }
        return classLoaderScope.getLocalClassLoader();
    }

    @Override
    public DynamicObject getAsDynamicObject() {
        if (dynamicObject == null) {
            dynamicObject = new BeanDynamicObject(this);
        }
        return dynamicObject;
    }
}
