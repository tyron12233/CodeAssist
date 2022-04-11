package com.tyron.builder.api.internal.reflect.service.scopes;

import static com.tyron.builder.api.internal.Cast.uncheckedCast;

import com.tyron.builder.api.StartParameter;
import com.tyron.builder.api.configuration.TaskNameResolver;
import com.tyron.builder.api.execution.ProjectConfigurer;
import com.tyron.builder.api.execution.TaskSelector;
import com.tyron.builder.api.execution.plan.DefaultNodeValidator;
import com.tyron.builder.api.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.api.execution.plan.TaskDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeDependencyResolver;
import com.tyron.builder.api.execution.plan.TaskNodeFactory;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.GUtil;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.event.DefaultListenerManager;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.file.Deleter;
import com.tyron.builder.api.internal.file.FileException;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.hash.StreamHasher;
import com.tyron.builder.api.internal.id.UniqueId;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.api.internal.nativeintegration.FileSystem;
import com.tyron.builder.api.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.api.internal.operations.BuildOperationExecutor;
import com.tyron.builder.api.internal.operations.BuildOperationQueueFactory;
import com.tyron.builder.api.internal.operations.DefaultBuildOperationQueueFactory;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.resources.DefaultResourceLockCoordinationService;
import com.tyron.builder.api.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.api.internal.scopeids.id.BuildInvocationScopeId;
import com.tyron.builder.api.internal.service.scopes.Scopes;
import com.tyron.builder.api.internal.snapshot.CaseSensitivity;
import com.tyron.builder.api.internal.work.WorkerLeaseService;
import com.tyron.builder.api.util.GFileUtils;
import com.tyron.builder.cache.StringInterner;
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
import com.tyron.builder.configuration.BuildOperationFiringProjectsPreparer;
import com.tyron.builder.configuration.BuildTreePreparingProjectsPreparer;
import com.tyron.builder.configuration.DefaultProjectsPreparer;
import com.tyron.builder.configuration.InitScriptProcessor;
import com.tyron.builder.configuration.ProjectsPreparer;
import com.tyron.builder.execution.CompositeAwareTaskSelector;
import com.tyron.builder.execution.TaskPathProjectEvaluator;
import com.tyron.builder.execution.plan.ExecutionPlanFactory;
import com.tyron.builder.initialization.BuildLoader;
import com.tyron.builder.initialization.DefaultGradlePropertiesController;
import com.tyron.builder.initialization.DefaultGradlePropertiesLoader;
import com.tyron.builder.initialization.DefaultProjectDescriptorRegistry;
import com.tyron.builder.initialization.DefaultSettings;
import com.tyron.builder.initialization.DefaultSettingsLoaderFactory;
import com.tyron.builder.initialization.DefaultSettingsPreparer;
import com.tyron.builder.initialization.Environment;
import com.tyron.builder.initialization.GradlePropertiesController;
import com.tyron.builder.initialization.IGradlePropertiesLoader;
import com.tyron.builder.initialization.InitScriptHandler;
import com.tyron.builder.initialization.InstantiatingBuildLoader;
import com.tyron.builder.initialization.ModelConfigurationListener;
import com.tyron.builder.initialization.NotifyingBuildLoader;
import com.tyron.builder.initialization.ProjectDescriptorRegistry;
import com.tyron.builder.initialization.ProjectPropertySettingBuildLoader;
import com.tyron.builder.initialization.SettingsLoaderFactory;
import com.tyron.builder.initialization.SettingsLocation;
import com.tyron.builder.initialization.SettingsPreparer;
import com.tyron.builder.initialization.SettingsProcessor;
import com.tyron.builder.initialization.layout.ResolvedBuildLayout;
import com.tyron.builder.internal.build.BuildModelControllerServices;
import com.tyron.builder.internal.build.BuildOperationFiringBuildWorkPreparer;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.BuildWorkPreparer;
import com.tyron.builder.internal.build.DefaultBuildWorkGraphController;
import com.tyron.builder.internal.build.DefaultBuildWorkPreparer;
import com.tyron.builder.internal.build.DefaultPublicBuildPath;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.internal.buildTree.BuildInclusionCoordinator;
import com.tyron.builder.internal.buildTree.BuildModelParameters;
import com.tyron.builder.internal.composite.DefaultBuildIncluder;
import com.tyron.builder.internal.resource.StringTextResource;
import com.tyron.builder.internal.resource.TextFileResourceLoader;
import com.tyron.builder.internal.resource.TextResource;
import com.tyron.builder.internal.resource.local.FileResourceListener;
import com.tyron.builder.internal.vfs.FileSystemAccess;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

@SuppressWarnings({"unused"})
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
            registration.add(DefaultBuildWorkGraphController.class);
            registration.add(TaskPathProjectEvaluator.class);

//            registration.add(DefaultResourceLockCoordinationService.class);
            registration.add(DefaultSettingsLoaderFactory.class);
            registration.add(ResolvedBuildLayout.class);
            registration.add(DefaultBuildIncluder.class);

            supplier.applyServicesTo(registration, this);
        });
    }


    TextFileResourceLoader createTextFileResourceLoader() {
        return new TextFileResourceLoader() {
            @Override
            public TextResource loadFile(String description, @Nullable File sourceFile) {
                return new StringTextResource(description, GFileUtils.readFileToString(sourceFile));
            }
        };
    }

    InitScriptHandler createInitScriptHandler(BuildOperationExecutor buildOperationExecutor, TextFileResourceLoader resourceLoader) {
        return new InitScriptHandler(new InitScriptProcessor() {
            @Override
            public void process(Object initScript, GradleInternal gradle) {

            }
        }, buildOperationExecutor, resourceLoader);
    }

    SettingsProcessor createSettingsProcessor() {
        return new SettingsProcessor() {
            @Override
            public SettingsInternal process(GradleInternal gradle,
                                            SettingsLocation settingsLocation,
                                            ClassLoaderScope buildRootClassLoaderScope,
                                            StartParameter startParameter) {
                return new DefaultSettings(
                        get(ServiceRegistryFactory.class),
                        gradle,
                        settingsLocation.getSettingsDir(),
                        startParameter
                );
            }
        };
    }

    SettingsPreparer createSettingsPreparer(SettingsLoaderFactory factory) {
        return new DefaultSettingsPreparer(factory);
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

    protected BuildWorkPreparer createWorkPreparer(BuildOperationExecutor buildOperationExecutor, ExecutionPlanFactory executionPlanFactory) {
        return new BuildOperationFiringBuildWorkPreparer(
                buildOperationExecutor,
                new DefaultBuildWorkPreparer(
                        executionPlanFactory
                ));
    }

    protected TaskSelector createTaskSelector(GradleInternal gradle, BuildStateRegistry buildStateRegistry, ProjectConfigurer projectConfigurer) {
        return new CompositeAwareTaskSelector(gradle, buildStateRegistry, projectConfigurer, new TaskNameResolver());
    }

    protected ProjectsPreparer createBuildConfigurer(
            ProjectConfigurer projectConfigurer,
//            BuildSourceBuilder buildSourceBuilder,
            BuildStateRegistry buildStateRegistry,
            BuildInclusionCoordinator inclusionCoordinator,
            BuildLoader buildLoader,
            ListenerManager listenerManager,
            BuildOperationExecutor buildOperationExecutor,
            BuildModelParameters buildModelParameters
    ) {
        ModelConfigurationListener modelConfigurationListener = listenerManager.getBroadcaster(
                ModelConfigurationListener.class);
        return new BuildOperationFiringProjectsPreparer(
                new BuildTreePreparingProjectsPreparer(
                        new DefaultProjectsPreparer(
                                projectConfigurer,
                                buildModelParameters,
                                modelConfigurationListener,
                                buildOperationExecutor,
                                buildStateRegistry),
                        buildLoader,
                        inclusionCoordinator),
                buildOperationExecutor);
    }

    protected BuildLoader createBuildLoader(
            GradleProperties gradleProperties,
            BuildOperationExecutor buildOperationExecutor,
            ListenerManager listenerManager
    ) {
        return new NotifyingBuildLoader(
                new ProjectPropertySettingBuildLoader(
                        gradleProperties,
                        new InstantiatingBuildLoader(),
                        listenerManager.getBroadcaster(FileResourceListener.class)
                ),
                buildOperationExecutor
        );
    }

    Environment createEnvironment() {
        return new Environment() {
            @Nullable
            @Override
            public Map<String, String> propertiesFile(File propertiesFile) {
                if (propertiesFile.isFile()) {
                    return uncheckedCast(GUtil.loadProperties(propertiesFile));
                }
                return null;
            }

            @Override
            public Properties getSystemProperties() {
                return new DefaultProperties(System.getenv());
            }

            @Override
            public Properties getVariables() {
                return new DefaultProperties(System.getenv());
            }
        };
    }

    private static class DefaultProperties implements Environment.Properties {
        private final Map<String, String> map;

        private DefaultProperties(Map<String, String> map) {
            this.map = map;
        }

        @Override
        public Map<String, String> byNamePrefix(String prefix) {
            return map.entrySet().stream().filter(it -> it.getKey().equals(prefix))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }
    }

    protected GradlePropertiesController createGradlePropertiesController(
            IGradlePropertiesLoader propertiesLoader
    ) {
        return new DefaultGradlePropertiesController(propertiesLoader);
    }

    protected IGradlePropertiesLoader createGradlePropertiesLoader(
            Environment environment
    ) {
        return new DefaultGradlePropertiesLoader(
                (StartParameterInternal) get(StartParameter.class),
                environment
        );
    }

    protected GradleProperties createGradleProperties(
            GradlePropertiesController gradlePropertiesController
    ) {
        return gradlePropertiesController.getGradleProperties();
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

    ExecutionPlanFactory createExecutionPlanFactory(
            GradleInternal gradleInternal,
            TaskNodeFactory taskNodeFactory,
            TaskDependencyResolver dependencyResolver,
            ExecutionNodeAccessHierarchies executionNodeAccessHierarchies,
            ResourceLockCoordinationService lockCoordinationService
    ) {
        return new ExecutionPlanFactory(
                gradleInternal.getIdentityPath().toString(),
                taskNodeFactory,
                dependencyResolver,
                executionNodeAccessHierarchies.getOutputHierarchy(),
                executionNodeAccessHierarchies.getDestroyableHierarchy(),
                lockCoordinationService
        );
    }

    ExecutionNodeAccessHierarchies createExecutionNodeAccessHierarchies() {
        return new ExecutionNodeAccessHierarchies(CaseSensitivity.CASE_INSENSITIVE, FileSystems.getDefault());
    }

    protected PublicBuildPath createPublicBuildPath(BuildState buildState) {
        return new DefaultPublicBuildPath(buildState.getIdentityPath());
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
