package com.tyron.builder.api.internal.project;

import com.google.common.collect.ImmutableList;
import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.artifacts.DefaultBuildIdentifier;
import com.tyron.builder.api.internal.classpath.ClassPath;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistryBuilder;
import com.tyron.builder.api.internal.reflect.service.scopes.BuildScopeServices;
import com.tyron.builder.api.internal.reflect.service.scopes.GlobalServices;
import com.tyron.builder.api.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.internal.work.WorkerLeaseRegistry;
import com.tyron.builder.api.internal.work.WorkerLeaseService;
import com.tyron.builder.api.project.TestBuildScopeServices;
import com.tyron.builder.api.util.GFileUtils;
import com.tyron.builder.api.util.Path;
import com.tyron.builder.initialization.BuildRequestMetaData;
import com.tyron.builder.initialization.DefaultBuildCancellationToken;
import com.tyron.builder.initialization.DefaultBuildRequestMetaData;
import com.tyron.builder.initialization.DefaultProjectDescriptor;
import com.tyron.builder.initialization.NoOpBuildEventConsumer;
import com.tyron.builder.initialization.ProjectDescriptorRegistry;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.build.AbstractBuildState;
import com.tyron.builder.internal.build.BuildModelControllerServices;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.RootBuildState;
import com.tyron.builder.internal.buildTree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildTree.BuildTreeModelControllerServices;
import com.tyron.builder.internal.buildTree.BuildTreeState;
import com.tyron.builder.internal.buildTree.RunTasksRequirements;
import com.tyron.builder.internal.composite.IncludedBuildInternal;
import com.tyron.builder.internal.logging.services.LoggingServiceRegistry;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;
import com.tyron.builder.internal.service.scopes.PluginServiceRegistry;
import com.tyron.builder.internal.session.BuildSessionState;
import com.tyron.builder.internal.session.state.CrossBuildSessionState;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Do not use this for executing tasks
 */
public class ProjectBuilderImpl {

    private static ServiceRegistry globalServices;

    public ProjectInternal createProject(String name, File inputProjectDir, File gradleUserHomeDir) {
        System.setProperty("user.dir", inputProjectDir.getAbsolutePath());

        final File projectDir = prepareProjectDir(inputProjectDir);
        File userHomeDir = gradleUserHomeDir == null ? new File(projectDir, "userHome") : GFileUtils.canonicalize(gradleUserHomeDir);
        StartParameterInternal startParameter = new StartParameterInternal();
        startParameter.setGradleUserHomeDir(userHomeDir);
        startParameter.setProjectDir(inputProjectDir);
        startParameter.setTaskNames(ImmutableList.of("testTask"));
        startParameter.setMaxWorkerCount(5);

        final ServiceRegistry globalServices = getGlobalServices(Collections.emptyList());

        BuildRequestMetaData buildRequestMetaData = new DefaultBuildRequestMetaData(Time.currentTimeMillis());
        CrossBuildSessionState crossBuildSessionState = new CrossBuildSessionState(globalServices, startParameter);
        GradleUserHomeScopeServiceRegistry userHomeServices = userHomeServicesOf(globalServices);
        BuildSessionState buildSessionState = new BuildSessionState(userHomeServices, crossBuildSessionState, startParameter, buildRequestMetaData, ClassPath.EMPTY, new DefaultBuildCancellationToken(), buildRequestMetaData.getClient(), new NoOpBuildEventConsumer());
        BuildTreeModelControllerServices.Supplier modelServices = buildSessionState.getServices().get(BuildTreeModelControllerServices.class).servicesForBuildTree(new RunTasksRequirements(startParameter));
        BuildTreeState buildTreeState = new BuildTreeState(buildSessionState.getServices(), modelServices);
        TestRootBuild build = new TestRootBuild(projectDir, startParameter, buildTreeState);

        BuildScopeServices buildServices = build.getBuildServices();
        buildServices.get(BuildStateRegistry.class).attachRootBuild(build);

//        // Take a root worker lease; this won't ever be released as ProjectBuilder has no lifecycle
        ResourceLockCoordinationService coordinationService = buildServices.get(ResourceLockCoordinationService.class);
        WorkerLeaseService workerLeaseService = buildServices.get(WorkerLeaseService.class);
//        WorkerLeaseRegistry.WorkerLeaseCompletion workerLease = workerLeaseService.maybeStartWorker();

        GradleInternal gradle = build.getMutableModel();
//        gradle.setIncludedBuilds(Collections.emptyList());

        WorkerLeaseRegistry.WorkerLeaseCompletion lease =
                workerLeaseService.startWorker();

        try {

            ProjectDescriptorRegistry projectDescriptorRegistry = buildServices.get(ProjectDescriptorRegistry.class);
            DefaultProjectDescriptor projectDescriptor =
                    new DefaultProjectDescriptor(null, name, projectDir, projectDescriptorRegistry,
                            buildServices.get(FileResolver.class));
            projectDescriptorRegistry.addProject(projectDescriptor);

            ProjectStateRegistry projectStateRegistry = buildServices.get(ProjectStateRegistry.class);
            ProjectStateUnk projectState = projectStateRegistry.registerProject(build,
                    projectDescriptor);
            projectState.createMutableModel();
            ProjectInternal project = projectState.getMutableModel();

            gradle.setRootProject(project);
            gradle.setDefaultProject(project);
            return project;
        } finally {
            lease.leaseFinish();
        }
        // Lock root project; this won't ever be released as ProjectBuilder has no lifecycle
//        coordinationService.withStateLock(DefaultResourceLockCoordinationService.lock(project.getOwner().getAccessLock()));
    }

    private GradleUserHomeScopeServiceRegistry userHomeServicesOf(ServiceRegistry globalServices) {
        return globalServices.get(GradleUserHomeScopeServiceRegistry.class);
    }

    public synchronized static ServiceRegistry getGlobalServices(List<PluginServiceRegistry> pluginServiceRegistries) {
        if (globalServices == null) {
            globalServices = createGlobalServices(pluginServiceRegistries);
        }
        return globalServices;
    }

    public static ServiceRegistry createGlobalServices(List<PluginServiceRegistry> pluginServiceRegistries) {
        LoggingServiceRegistry serviceRegistry =
                LoggingServiceRegistry.newCommandLineProcessLogging();
        return ServiceRegistryBuilder
                .builder()
                .parent(serviceRegistry)
                .displayName("global services")
                .provider(new GlobalServices())
                .build();
    }

    public File prepareProjectDir(@Nullable final File projectDir) {
        if (projectDir == null) {
            throw new IllegalArgumentException();
        }
        return GFileUtils.canonicalize(projectDir);
    }

    private static class TestRootBuild extends AbstractBuildState implements RootBuildState {
        private final GradleInternal gradle;
        final BuildScopeServices buildServices;

        public TestRootBuild(File rootProjectDir, StartParameterInternal startParameter, BuildTreeState buildTreeState) {
            super(buildTreeState, BuildDefinition.fromStartParameter(startParameter, rootProjectDir, null), null);
            this.buildServices = getBuildServices();
            this.gradle = buildServices.get(GradleInternal.class);
        }

        @Override
        protected BuildScopeServices prepareServices(BuildTreeState buildTree, BuildDefinition buildDefinition, BuildModelControllerServices.Supplier supplier) {
            final File homeDir = new File(buildDefinition.getBuildRootDir(), "gradleHome");
            return new TestBuildScopeServices(buildTree.getServices(), homeDir, supplier);
        }

        @Override
        public BuildScopeServices getBuildServices() {
            return super.getBuildServices();
        }

        @Override
        public void ensureProjectsLoaded() {
        }

        @Override
        public void ensureProjectsConfigured() {
        }

        @Override
        public BuildIdentifier getBuildIdentifier() {
            return DefaultBuildIdentifier.ROOT;
        }

        @Override
        public Path getIdentityPath() {
            return Path.ROOT;
        }

        @Override
        public boolean isImplicitBuild() {
            return false;
        }

        @Override
        public Path getCurrentPrefixForProjectsInChildBuilds() {
            return Path.ROOT;
        }

        @Override
        public Path calculateIdentityPathForProject(Path projectPath) {
            return projectPath;
        }

        @Override
        public StartParameterInternal getStartParameter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T run(Function<? super BuildTreeLifecycleController, T> action) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IncludedBuildInternal getModel() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<Pair<ModuleVersionIdentifier, ProjectComponentIdentifier>> getAvailableModules() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProjectComponentIdentifier idToReferenceProjectFromAnotherBuild(ProjectComponentIdentifier identifier) {
            throw new UnsupportedOperationException();
        }

        @Override
        public File getBuildRootDir() {
            return getBuildServices().get(BuildDefinition.class).getBuildRootDir();
        }

        @Override
        public GradleInternal getMutableModel() {
            return gradle;
        }
    }
}
