package com.tyron.builder.api.internal.reflect.service.scopes;

import com.tyron.builder.api.execution.plan.DefaultNodeValidator;
import com.tyron.builder.api.execution.plan.DefaultPlanExecutor;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.api.execution.plan.PlanExecutor;
import com.tyron.builder.api.execution.plan.TaskDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeFactory;
import com.tyron.builder.api.initialization.BuildCancellationToken;
import com.tyron.builder.api.internal.DefaultGradle;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.concurrent.DefaultExecutorFactory;
import com.tyron.builder.api.internal.concurrent.ExecutorFactory;
import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.Stat;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.hash.StreamHasher;
import com.tyron.builder.api.internal.id.UniqueId;
import com.tyron.builder.api.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.api.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.operations.BuildOperationIdFactory;
import com.tyron.builder.api.internal.operations.BuildOperationListener;
import com.tyron.builder.api.internal.operations.BuildOperationQueueFactory;
import com.tyron.builder.api.internal.operations.DefaultBuildOperationExecutor;
import com.tyron.builder.api.internal.operations.DefaultBuildOperationIdFactory;
import com.tyron.builder.api.internal.operations.DefaultBuildOperationQueueFactory;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.resources.DefaultResourceLockCoordinationService;
import com.tyron.builder.api.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.api.internal.scopeids.id.BuildInvocationScopeId;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.time.Clock;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.internal.work.DefaultWorkerLeaseService;
import com.tyron.builder.api.internal.work.WorkerLeaseService;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.StringInterner;
import com.tyron.builder.cache.internal.scopes.DefaultBuildScopedCache;
import com.tyron.builder.cache.scopes.BuildScopedCache;
import com.tyron.builder.caching.configuration.internal.BuildCacheConfigurationInternal;
import com.tyron.builder.caching.internal.BuildCacheController;
import com.tyron.builder.caching.internal.controller.RootBuildCacheControllerRef;
import com.tyron.builder.caching.internal.origin.OriginMetadataFactory;
import com.tyron.builder.caching.internal.origin.OriginMetadataFactory.HostnameLookup;
import com.tyron.builder.caching.internal.packaging.BuildCacheEntryPacker;
import com.tyron.builder.caching.internal.packaging.impl.DefaultTarPackerFileSystemSupport;
import com.tyron.builder.caching.internal.packaging.impl.FilePermissionAccess;
import com.tyron.builder.caching.internal.packaging.impl.GZipBuildCacheEntryPacker;
import com.tyron.builder.caching.internal.packaging.impl.TarBuildCacheEntryPacker;
import com.tyron.builder.caching.internal.packaging.impl.TarPackerFileSystemSupport;
import com.tyron.builder.concurrent.ParallelismConfiguration;
import com.tyron.builder.initialization.DefaultProjectDescriptorRegistry;
import com.tyron.builder.initialization.ProjectDescriptorRegistry;
import com.tyron.builder.internal.build.BuildModelControllerServices;
import com.tyron.builder.internal.vfs.FileSystemAccess;
import com.tyron.common.TestUtil;

import java.io.File;

public class BuildScopeServices extends DefaultServiceRegistry {

    public BuildScopeServices(ServiceRegistry parent, BuildModelControllerServices.Supplier supplier) {
        super(parent);


        addProvider(new Object() {

            private static final String GRADLE_VERSION_KEY = "gradleVersion";

            // This needs to go here instead of being “build tree” scoped due to the GradleBuild task.
            // Builds launched by that task are part of the same build tree, but should have their own invocation ID.
            // Such builds also have their own root Gradle object.
            BuildInvocationScopeId createBuildInvocationScopeId(GradleInternal gradle) {
                GradleInternal rootGradle = gradle.getRoot();
                if (gradle == rootGradle) {
                    return new BuildInvocationScopeId(UniqueId.generate());
                } else {
                    return rootGradle.getServices().get(BuildInvocationScopeId.class);
                }
            }

            OriginMetadataFactory.HostnameLookup createHostNameLookup() {
                return new OriginMetadataFactory.HostnameLookup() {
                    @Override
                    public String getHostname() {
                        return "TEST";
                    }
                };
            }

            TarPackerFileSystemSupport createPackerFileSystemSupport(Deleter deleter) {
                return new DefaultTarPackerFileSystemSupport(deleter);
            }

            BuildCacheEntryPacker createResultPacker(
                    TarPackerFileSystemSupport fileSystemSupport,
                    FileSystem fileSystem,
                    StreamHasher fileHasher,
                    StringInterner stringInterner
            ) {
                return new GZipBuildCacheEntryPacker(
                        new TarBuildCacheEntryPacker(fileSystemSupport, new FilePermissionsAccessAdapter(fileSystem), fileHasher, stringInterner));
            }

            OriginMetadataFactory createOriginMetadataFactory(
                    BuildInvocationScopeId buildInvocationScopeId,
                    GradleInternal gradleInternal,
                    HostnameLookup hostnameLookup
            ) {
                return new OriginMetadataFactory(
                        "Test",
                        "ANDROID",
                        buildInvocationScopeId.getId().asString(),
                        properties -> properties.setProperty(GRADLE_VERSION_KEY, DocumentationRegistry.GradleVersion
                                .current().getVersion()),
                        hostnameLookup::getHostname
                );
            }

            BuildCacheController createBuildCacheController(
                    ServiceRegistry serviceRegistry,
                    BuildCacheConfigurationInternal buildCacheConfiguration,
                    BuildOperationExecutor buildOperationExecutor,
                    InstantiatorFactory instantiatorFactory,
                    GradleInternal gradle,
                    RootBuildCacheControllerRef rootControllerRef,
                    TemporaryFileProvider temporaryFileProvider,
                    FileSystemAccess fileSystemAccess,
                    BuildCacheEntryPacker packer,
                    OriginMetadataFactory originMetadataFactory,
                    StringInterner stringInterner
            ) {
                if (isRoot(gradle) || isGradleBuildTaskRoot(rootControllerRef)) {
                    return doCreateBuildCacheController(serviceRegistry, buildCacheConfiguration, buildOperationExecutor, instantiatorFactory, gradle, temporaryFileProvider, fileSystemAccess, packer, originMetadataFactory, stringInterner);
                } else {
                    // must be an included build or buildSrc
                    throw new UnsupportedOperationException("Not yet implemented!");
//                    return rootControllerRef.getForNonRootBuild()
                }
            }

            private boolean isGradleBuildTaskRoot(RootBuildCacheControllerRef rootControllerRef) {
                // GradleBuild tasks operate with their own build session and tree scope.
                // Therefore, they have their own RootBuildCacheControllerRef.
                // This prevents them from reusing the build cache configuration defined by the root.
                // There is no way to detect that a Gradle instance represents a GradleBuild invocation.
                // If there were, that would be a better heuristic than this.
                return !rootControllerRef.isSet();
            }

            private boolean isRoot(GradleInternal gradle) {
                return gradle.isRootBuild();
            }

            private BuildCacheController doCreateBuildCacheController(
                    ServiceRegistry serviceRegistry,
                    BuildCacheConfigurationInternal buildCacheConfiguration,
                    BuildOperationExecutor buildOperationExecutor,
                    InstantiatorFactory instantiatorFactory,
                    GradleInternal gradle,
                    TemporaryFileProvider temporaryFileProvider,
                    FileSystemAccess fileSystemAccess,
                    BuildCacheEntryPacker packer,
                    OriginMetadataFactory originMetadataFactory,
                    StringInterner stringInterner
            ) {
                throw new UnsupportedOperationException("Not yet implemented");
            }
        });

        register(registration -> {
            registration.add(ProjectFactory.class);
            registration.add(DefaultNodeValidator.class);
            registration.add(TaskNodeFactory.class);
            registration.add(TaskNodeDependencyResolver.class);
//            registration.add(WorkNodeDependencyResolver.class);
            registration.add(TaskDependencyResolver.class);

            registration.add(DefaultResourceLockCoordinationService.class);

            supplier.applyServicesTo(registration, this);
        });
    }

    protected DefaultListenerManager createListenerManager(DefaultListenerManager listenerManager) {
        return listenerManager.createChild(Scopes.Build.class);
    }

    BuildOperationQueueFactory createBuildOperationQueueFactory(
            WorkerLeaseService workerLeaseService
    ) {
        return new DefaultBuildOperationQueueFactory(workerLeaseService);
    }

    protected ProjectDescriptorRegistry createProjectDescriptorRegistry() {
        return new DefaultProjectDescriptorRegistry();
    }

//    BuildOperationExecutor createBuildOperationExecutor(
//            BuildOperationListener buildOperationListener,
//            ProgressLoggerFactory progressLoggerFactory,
//            BuildOperationQueueFactory buildOperationQueueFactory,
//            ExecutorFactory executorFactory,
//            BuildOperationIdFactory buildOperationIdFactory
//    ) {
//        return new DefaultBuildOperationExecutor(buildOperationListener, Time.clock(),
//                progressLoggerFactory, buildOperationQueueFactory, executorFactory, new ParallelismConfiguration() {
//            @Override
//            public boolean isParallelProjectExecutionEnabled() {
//                return false;
//            }
//
//            @Override
//            public void setParallelProjectExecutionEnabled(boolean parallelProjectExecution) {
//
//            }
//
//            @Override
//            public int getMaxWorkerCount() {
//                return 1;
//            }
//
//            @Override
//            public void setMaxWorkerCount(int maxWorkerCount) {
//
//            }
//        }, buildOperationIdFactory);
//    }

    PlanExecutor createPlanExecutor(
            ExecutorFactory factory,
            WorkerLeaseService service,
            ResourceLockCoordinationService resourceLockService
    ) {
        return new DefaultPlanExecutor(
            factory,
            service,
            new BuildCancellationToken() {

                @Override
                public boolean isCancellationRequested() {
                    return false;
                }

                @Override
                public void cancel() {

                }

                @Override
                public boolean addCallback(Runnable cancellationHandler) {
                    return false;
                }

                @Override
                public void removeCallback(Runnable cancellationHandler) {

                }
            },
            resourceLockService
        );
    }

    ExecutionNodeAccessHierarchies createExecutionNodeAccessHierarchies() {
        return new ExecutionNodeAccessHierarchies(CaseSensitivity.CASE_INSENSITIVE, FileSystems.getDefault());
    }

//    protected TaskStatistics createTaskStatistics() {
//        return new TaskStatistics();
//    }

    protected BuildScopeServiceRegistryFactory createServiceRegistryFactory(final ServiceRegistry services) {
        return new BuildScopeServiceRegistryFactory(services);
    }

    private static final class FilePermissionsAccessAdapter implements FilePermissionAccess {

        private final FileSystem fileSystem;

        public FilePermissionsAccessAdapter(FileSystem fileSystem) {
            this.fileSystem = fileSystem;
        }

        @Override
        public int getUnixMode(File f) throws FileException {
            return fileSystem.getUnixMode(f);
        }

        @Override
        public void chmod(File file, int mode) throws FileException {
            fileSystem.chmod(file, mode);
        }
    }
}
