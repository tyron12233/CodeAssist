package org.gradle.initialization;

import com.tyron.groovy.DexBackedURLClassLoader;

import org.codehaus.groovy.reflection.android.AndroidSupport;
import org.gradle.api.internal.ClassPathRegistry;
import org.gradle.internal.classloader.FilteringClassLoader;
import org.gradle.internal.reflect.Instantiator;

public class DefaultClassLoaderRegistry implements ClassLoaderRegistry {
    private final ClassLoader apiOnlyClassLoader;
    private final ClassLoader apiAndPluginsClassLoader;
    private final ClassLoader pluginsClassLoader;
    private final FilteringClassLoader.Spec gradleApiSpec;
    private final MixInLegacyTypesClassLoader.Spec workerExtensionSpec;
    private final Instantiator instantiator;

    public DefaultClassLoaderRegistry(ClassPathRegistry classPathRegistry, LegacyTypesSupport legacyTypesSupport, Instantiator instantiator) {
        this.instantiator = instantiator;
        ClassLoader runtimeClassLoader = getClass().getClassLoader();
        if (AndroidSupport.isRunningAndroid()) {
            runtimeClassLoader = new DexBackedURLClassLoader(runtimeClassLoader);
        }
        this.apiOnlyClassLoader = restrictToGradleApi(runtimeClassLoader);
        this.pluginsClassLoader = new MixInLegacyTypesClassLoader(runtimeClassLoader, classPathRegistry.getClassPath("GRADLE_EXTENSIONS"), legacyTypesSupport);
        this.gradleApiSpec = apiSpecFor(pluginsClassLoader);
        this.workerExtensionSpec = new MixInLegacyTypesClassLoader.Spec("legacy-mixin-loader", classPathRegistry.getClassPath("GRADLE_WORKER_EXTENSIONS").getAsURLs());
        this.apiAndPluginsClassLoader = restrictTo(gradleApiSpec, pluginsClassLoader);
    }

    private ClassLoader restrictToGradleApi(ClassLoader classLoader) {
        return restrictTo(apiSpecFor(classLoader), classLoader);
    }

    private static ClassLoader restrictTo(FilteringClassLoader.Spec spec, ClassLoader parent) {
        return new FilteringClassLoader(parent, spec);
    }

    private FilteringClassLoader.Spec apiSpecFor(ClassLoader classLoader) {
        FilteringClassLoader.Spec apiSpec = new FilteringClassLoader.Spec();
        GradleApiSpecProvider.Spec apiAggregate = new GradleApiSpecAggregator(classLoader, instantiator).aggregate();
        for (String resource : apiAggregate.getExportedResources()) {
            apiSpec.allowResource(resource);
        }
        for (String resourcePrefix : apiAggregate.getExportedResourcePrefixes()) {
            apiSpec.allowResources(resourcePrefix);
        }
        for (Class<?> clazz : apiAggregate.getExportedClasses()) {
            apiSpec.allowClass(clazz);
        }
        for (String packageName : apiAggregate.getExportedPackages()) {
            apiSpec.allowPackage(packageName);
        }
        return apiSpec;
    }

    @Override
    public ClassLoader getRuntimeClassLoader() {
        return getClass().getClassLoader();
    }

    @Override
    public ClassLoader getGradleApiClassLoader() {
        return apiAndPluginsClassLoader;
    }

    @Override
    public ClassLoader getPluginsClassLoader() {
        return pluginsClassLoader;
    }

    @Override
    public ClassLoader getGradleCoreApiClassLoader() {
        return apiOnlyClassLoader;
    }

    @Override
    public FilteringClassLoader.Spec getGradleApiFilterSpec() {
        return new FilteringClassLoader.Spec(gradleApiSpec);
    }

    @Override
    public MixInLegacyTypesClassLoader.Spec getGradleWorkerExtensionSpec() {
        return workerExtensionSpec;
    }
}
