package com.tyron.builder.initialization;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.StartParameter;
import com.tyron.builder.api.UnknownProjectException;
import com.tyron.builder.api.initialization.ConfigurableIncludedBuild;
import com.tyron.builder.api.initialization.ProjectDescriptor;
import com.tyron.builder.api.initialization.Settings;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.project.ProjectRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.providers.ProviderFactory;
import com.tyron.builder.caching.configuration.BuildCacheConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

public class DefaultSettings implements SettingsInternal {
//    private ScriptSource settingsScript;

    private StartParameter startParameter;

    private File settingsDir;

    private DefaultProjectDescriptor rootProjectDescriptor;

    private DefaultProjectDescriptor defaultProjectDescriptor;

    private final GradleInternal gradle;

//    private final ClassLoaderScope classLoaderScope;
//    private final ClassLoaderScope baseClassLoaderScope;
//    private final ScriptHandler scriptHandler;
    private final ServiceRegistry services;

    private final List<IncludedBuildSpec> includedBuildSpecs = new ArrayList<>();
//    private final DependencyResolutionManagementInternal dependencyResolutionManagement;


    public DefaultSettings(
            ServiceRegistryFactory serviceRegistryFactory,
            GradleInternal gradle,
//            ClassLoaderScope classLoaderScope,
//            ClassLoaderScope baseClassLoaderScope,
//            ScriptHandler settingsScriptHandler,
            File settingsDir,
//            ScriptSource settingsScript,
            StartParameter startParameter
    ) {
        this.gradle = gradle;
//        this.classLoaderScope = classLoaderScope;
//        this.baseClassLoaderScope = baseClassLoaderScope;
//        this.scriptHandler = settingsScriptHandler;
        this.settingsDir = settingsDir;
//        this.settingsScript = settingsScript;
        this.startParameter = startParameter;
        this.services = serviceRegistryFactory.createFor(this);
        this.rootProjectDescriptor = createProjectDescriptor(null, settingsDir.getName(), settingsDir);
//        this.dependencyResolutionManagement = services.get(DependencyResolutionManagementInternal.class);
    }

    public DefaultProjectDescriptor createProjectDescriptor(@Nullable DefaultProjectDescriptor parent, String name, File dir) {
        return new DefaultProjectDescriptor(parent, name, dir, getProjectDescriptorRegistry(), getFileResolver());
    }

    private ProjectDescriptorRegistry getProjectDescriptorRegistry() {
        return services.get(ProjectDescriptorRegistry.class);
    }

    private FileResolver getFileResolver() {
        return services.get(FileResolver.class);
    }

    @Override
    public void include(Iterable<String> projectPaths) {

    }

    @Override
    public void includeFlat(Iterable<String> projectNames) {

    }

    @Override
    public Settings getSettings() {
        return this;
    }

    @Override
    public File getSettingsDir() {
        return settingsDir;
    }

    @Override
    public File getRootDir() {
        return null;
    }

    @Override
    public ProjectDescriptor getRootProject() {
        return rootProjectDescriptor;
    }

    @Override
    public ProjectDescriptor project(String path) throws UnknownProjectException {
        return null;
    }

    @Nullable
    @Override
    public ProjectDescriptor findProject(String path) {
        return null;
    }

    @Override
    public ProjectDescriptor project(File projectDir) throws UnknownProjectException {
        return null;
    }

    @Nullable
    @Override
    public ProjectDescriptor findProject(File projectDir) {
        return null;
    }

    @Override
    public ProviderFactory getProviders() {
        return null;
    }

    @Override
    public GradleInternal getGradle() {
        return gradle;
    }

    @Override
    public void includeBuild(Object rootProject) {

    }

    @Override
    public void includeBuild(Object rootProject, Action<ConfigurableIncludedBuild> configuration) {

    }

    @Override
    public BuildCacheConfiguration getBuildCache() {
        return null;
    }

    @Override
    public void buildCache(Action<? super BuildCacheConfiguration> action) {

    }

    @Override
    public void enableFeaturePreview(String name) {

    }

    @Override
    public StartParameter getStartParameter() {
        return null;
    }

    @Override
    public ProjectRegistry<DefaultProjectDescriptor> getProjectRegistry() {
        return getProjectDescriptorRegistry();
    }

    @Override
    public void setDefaultProject(DefaultProjectDescriptor defaultProjectDescriptor) {
        this.defaultProjectDescriptor = defaultProjectDescriptor;
    }

    @Override
    public DefaultProjectDescriptor getDefaultProject() {
        return defaultProjectDescriptor;
    }

    @Override
    public ClassLoaderScope getClassLoaderScope() {
        return null;
    }

    @Override
    public List<IncludedBuildSpec> getIncludedBuilds() {
        return Collections.emptyList();
    }
}
