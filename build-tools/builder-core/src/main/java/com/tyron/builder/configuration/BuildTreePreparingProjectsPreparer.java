package com.tyron.builder.configuration;

import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.initialization.BuildLoader;
import com.tyron.builder.internal.buildtree.BuildInclusionCoordinator;

public class BuildTreePreparingProjectsPreparer implements ProjectsPreparer {
    private final ProjectsPreparer delegate;
    private final BuildInclusionCoordinator coordinator;
//    private final BuildSourceBuilder buildSourceBuilder;
    private final BuildLoader buildLoader;

    public BuildTreePreparingProjectsPreparer(
            ProjectsPreparer delegate,
            BuildLoader buildLoader,
            BuildInclusionCoordinator coordinator
//            BuildSourceBuilder buildSourceBuilder
            ) {
        this.delegate = delegate;
        this.buildLoader = buildLoader;
        this.coordinator = coordinator;
//        this.buildSourceBuilder = buildSourceBuilder;
    }

    @Override
    public void prepareProjects(GradleInternal gradle) {
        // Setup classloader for root project, all other projects will be derived from this.
        SettingsInternal settings = gradle.getSettings();
        ClassLoaderScope settingsClassLoaderScope = settings.getClassLoaderScope();
        ClassLoaderScope buildSrcClassLoaderScope = settingsClassLoaderScope.createChild("buildSrc[" + gradle.getIdentityPath() + "]");
        gradle.setBaseProjectClassLoaderScope(buildSrcClassLoaderScope);
//        generateDependenciesAccessorsAndAssignPluginVersions(gradle.getServices(), settings, buildSrcClassLoaderScope);
        // attaches root project
        buildLoader.load(gradle.getSettings(), gradle);
        // Makes included build substitutions available
        if (gradle.isRootBuild()) {
            coordinator.registerGlobalLibrarySubstitutions();
        }
        // Build buildSrc and export classpath to root project
//        buildBuildSrcAndLockClassloader(gradle, buildSrcClassLoaderScope);

        delegate.prepareProjects(gradle);
    }

//    private void buildBuildSrcAndLockClassloader(GradleInternal gradle, ClassLoaderScope baseProjectClassLoaderScope) {
//        ClassPath buildSrcClassPath = buildSourceBuilder.buildAndGetClassPath(gradle);
//        baseProjectClassLoaderScope.export(buildSrcClassPath).lock();
//    }
//
//    private void generateDependenciesAccessorsAndAssignPluginVersions(ServiceRegistry services, SettingsInternal settings, ClassLoaderScope classLoaderScope) {
//        DependenciesAccessors accessors = services.get(DependenciesAccessors.class);
//        DependencyResolutionManagementInternal dm = services.get(DependencyResolutionManagementInternal.class);
//        dm.getDefaultLibrariesExtensionName().finalizeValue();
//        String defaultLibrary = dm.getDefaultLibrariesExtensionName().get();
//        File dependenciesFile = new File(settings.getSettingsDir(), "gradle/libs.versions.toml");
//        if (dependenciesFile.exists()) {
//            dm.versionCatalogs(catalogs -> {
//                VersionCatalogBuilder builder = catalogs.findByName(defaultLibrary);
//                if (builder == null) {
//                    builder = catalogs.create(defaultLibrary);
//                }
//                builder.from(services.get(FileCollectionFactory.class).fixed(dependenciesFile));
//            });
//        }
//        accessors.generateAccessors(dm.getDependenciesModelBuilders(), classLoaderScope, settings);
//    }
}
