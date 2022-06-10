package org.gradle.launcher;

import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.BuildIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.internal.artifacts.DefaultBuildIdentifier;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.ServiceRegistryBuilder;
import org.gradle.internal.service.scopes.BuildScopeServices;

import org.gradle.internal.service.scopes.GlobalServices;
import org.gradle.util.internal.GFileUtils;
import org.gradle.util.Path;
import org.gradle.internal.Pair;
import org.gradle.internal.build.AbstractBuildState;
import org.gradle.internal.build.BuildModelControllerServices;
import org.gradle.internal.build.RootBuildState;
import org.gradle.internal.buildtree.BuildTreeLifecycleController;
import org.gradle.internal.buildtree.BuildTreeState;
import org.gradle.internal.composite.IncludedBuildInternal;
import org.gradle.internal.logging.services.LoggingServiceRegistry;
import org.gradle.internal.nativeintegration.services.NativeServices;
import org.gradle.internal.service.scopes.GradleUserHomeScopeServiceRegistry;

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
