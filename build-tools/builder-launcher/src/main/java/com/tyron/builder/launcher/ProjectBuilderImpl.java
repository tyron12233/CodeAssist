package com.tyron.builder.launcher;

import com.tyron.builder.api.artifacts.ModuleVersionIdentifier;
import com.tyron.builder.api.artifacts.component.BuildIdentifier;
import com.tyron.builder.api.artifacts.component.ProjectComponentIdentifier;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.artifacts.DefaultBuildIdentifier;
import com.tyron.builder.internal.classpath.ClassPath;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistryBuilder;
import com.tyron.builder.internal.service.scopes.BuildScopeServices;

import com.tyron.builder.internal.service.scopes.GlobalServices;
import com.tyron.builder.util.internal.GFileUtils;
import com.tyron.builder.util.Path;
import com.tyron.builder.internal.Pair;
import com.tyron.builder.internal.build.AbstractBuildState;
import com.tyron.builder.internal.build.BuildModelControllerServices;
import com.tyron.builder.internal.build.RootBuildState;
import com.tyron.builder.internal.buildtree.BuildTreeLifecycleController;
import com.tyron.builder.internal.buildtree.BuildTreeState;
import com.tyron.builder.internal.composite.IncludedBuildInternal;
import com.tyron.builder.internal.logging.services.LoggingServiceRegistry;
import com.tyron.builder.internal.nativeintegration.services.NativeServices;
import com.tyron.builder.internal.service.scopes.GradleUserHomeScopeServiceRegistry;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;
import java.util.function.Function;

/**
 * Do not use this for executing tasks
 */
public class ProjectBuilderImpl {

    private static ServiceRegistry globalServices;

//    public ProjectInternal createProject(String name, File inputProjectDir, File gradleUserHomeDir) {
//        System.setProperty("user.dir", inputProjectDir.getAbsolutePath());
//
//        final File projectDir = prepareProjectDir(inputProjectDir);
//        File userHomeDir = gradleUserHomeDir == null ? new File(projectDir, "userHome") : GFileUtils.canonicalize(gradleUserHomeDir);
//        StartParameterInternal startParameter = new StartParameterInternal();
//        startParameter.setGradleUserHomeDir(userHomeDir);
//        startParameter.setProjectDir(inputProjectDir);
//        startParameter.setTaskNames(ImmutableList.of("testTask"));
//        startParameter.setMaxWorkerCount(5);
//
//        final ServiceRegistry globalServices = getGlobalServices(startParameter);
//
//        BuildRequestMetaData buildRequestMetaData = new DefaultBuildRequestMetaData(Time.currentTimeMillis());
//        CrossBuildSessionState crossBuildSessionState = new CrossBuildSessionState(globalServices, startParameter);
//        GradleUserHomeScopeServiceRegistry userHomeServices = userHomeServicesOf(globalServices);
//        BuildSessionState buildSessionState = new BuildSessionState(userHomeServices, crossBuildSessionState, startParameter, buildRequestMetaData, ClassPath.EMPTY, new DefaultBuildCancellationToken(), buildRequestMetaData.getClient(), new NoOpBuildEventConsumer());
//        BuildTreeModelControllerServices.Supplier modelServices = buildSessionState.getServices().get(BuildTreeModelControllerServices.class).servicesForBuildTree(new RunTasksRequirements(startParameter));
//        BuildTreeState buildTreeState = new BuildTreeState(buildSessionState.getServices(), modelServices);
//        TestRootBuild build = new TestRootBuild(projectDir, startParameter, buildTreeState);
//
//        BuildScopeServices buildServices = build.getBuildServices();
//        buildServices.get(BuildStateRegistry.class).attachRootBuild(build);
//
////        // Take a root worker lease; this won't ever be released as ProjectBuilder has no lifecycle
//        ResourceLockCoordinationService coordinationService = buildServices.get(ResourceLockCoordinationService.class);
//        WorkerLeaseService workerLeaseService = buildServices.get(WorkerLeaseService.class);
////        WorkerLeaseRegistry.WorkerLeaseCompletion workerLease = workerLeaseService.maybeStartWorker();
//
//        GradleInternal gradle = build.getMutableModel();
////        gradle.setIncludedBuilds(Collections.emptyList());
//
//        WorkerLeaseRegistry.WorkerLeaseCompletion lease =
//                workerLeaseService.startWorker();
//
//        try {
//
//            ProjectDescriptorRegistry projectDescriptorRegistry = buildServices.get(ProjectDescriptorRegistry.class);
//            DefaultProjectDescriptor projectDescriptor =
//                    new DefaultProjectDescriptor(null, name, projectDir, projectDescriptorRegistry,
//                            buildServices.get(FileResolver.class));
//            projectDescriptorRegistry.addProject(projectDescriptor);
//
//            ProjectStateRegistry projectStateRegistry = buildServices.get(ProjectStateRegistry.class);
//            ProjectStateUnk projectState = projectStateRegistry.registerProject(build,
//                    projectDescriptor);
//            projectState.createMutableModel();
//            ProjectInternal project = projectState.getMutableModel();
//
//            gradle.setRootProject(project);
//            gradle.setDefaultProject(project);
//            return project;
//        } finally {
//            lease.leaseFinish();
//        }
//        // Lock root project; this won't ever be released as ProjectBuilder has no lifecycle
////        coordinationService.withStateLock(DefaultResourceLockCoordinationService.lock(project.getOwner().getAccessLock()));
//    }

    private GradleUserHomeScopeServiceRegistry userHomeServicesOf(ServiceRegistry globalServices) {
        return globalServices.get(GradleUserHomeScopeServiceRegistry.class);
    }

    public synchronized static ServiceRegistry getGlobalServices(StartParameterInternal startParameterInternal) {
        if (globalServices == null) {
            globalServices = createGlobalServices(startParameterInternal);
        }
        return globalServices;
    }

    public static ServiceRegistry createGlobalServices(StartParameterInternal startParameterInternal) {
        NativeServices.initializeOnDaemon(startParameterInternal.getGradleUserHomeDir());
        return ServiceRegistryBuilder
                .builder()
                .parent(LoggingServiceRegistry.newEmbeddableLogging())
                .parent(NativeServices.getInstance())
                .displayName("global services")
                .provider(new GlobalServices(true, ClassPath.EMPTY))
                .build();
    }
}
