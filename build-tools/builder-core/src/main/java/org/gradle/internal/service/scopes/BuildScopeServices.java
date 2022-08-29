package org.gradle.internal.service.scopes;

import org.gradle.StartParameter;
import org.gradle.api.Project;
import org.gradle.api.internal.BuildDefinition;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.StartParameterInternal;
import org.gradle.api.internal.artifacts.DefaultModule;
import org.gradle.api.internal.artifacts.DependencyManagementServices;
import org.gradle.api.internal.artifacts.Module;
import org.gradle.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import org.gradle.api.internal.component.ComponentTypeRegistry;
import org.gradle.api.internal.component.DefaultComponentTypeRegistry;
import org.gradle.api.internal.file.DefaultFileOperations;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.temp.TemporaryFileProvider;
import org.gradle.api.internal.initialization.DefaultScriptClassPathResolver;
import org.gradle.api.internal.initialization.DefaultScriptHandlerFactory;
import org.gradle.api.internal.initialization.ScriptClassPathInitializer;
import org.gradle.api.internal.initialization.ScriptClassPathResolver;
import org.gradle.api.internal.initialization.ScriptHandlerFactory;
import org.gradle.api.internal.model.NamedObjectInstantiator;
import org.gradle.api.internal.plugins.DefaultPluginRegistry;
import org.gradle.api.internal.plugins.PluginInspector;
import org.gradle.api.internal.plugins.PluginRegistry;
import org.gradle.api.internal.project.DefaultProjectRegistry;
import org.gradle.api.internal.project.DefaultProjectTaskLister;
import org.gradle.api.internal.project.ProjectFactory;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.project.ProjectStateRegistry;
import org.gradle.api.internal.project.ProjectTaskLister;
import org.gradle.api.internal.project.taskfactory.AnnotationProcessingTaskFactory;
import org.gradle.api.internal.project.taskfactory.ITaskFactory;
import org.gradle.api.internal.project.taskfactory.TaskClassInfoStore;
import org.gradle.api.internal.project.taskfactory.TaskFactory;
import org.gradle.api.internal.properties.GradleProperties;
import org.gradle.api.internal.provider.DefaultProviderFactory;
import org.gradle.api.internal.provider.DefaultValueSourceProviderFactory;
import org.gradle.api.internal.provider.ValueSourceProviderFactory;
import org.gradle.api.internal.resources.ApiTextResourceAdapter;
import org.gradle.api.internal.resources.DefaultResourceHandler;
import org.gradle.api.internal.tasks.TaskStatistics;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.cache.CacheRepository;
import org.gradle.cache.FileLockManager;
import org.gradle.cache.internal.BuildScopeCacheDir;
import org.gradle.cache.internal.scopes.DefaultBuildScopedCache;
import org.gradle.cache.scopes.BuildScopedCache;
import org.gradle.cache.scopes.GlobalScopedCache;
import org.gradle.caching.internal.packaging.impl.FilePermissionAccess;
import org.gradle.configuration.BuildOperationFiringProjectsPreparer;
import org.gradle.configuration.BuildTreePreparingProjectsPreparer;
import org.gradle.configuration.CompileOperationFactory;
import org.gradle.configuration.DefaultProjectsPreparer;
import org.gradle.configuration.DefaultScriptPluginFactory;
import org.gradle.configuration.ImportsReader;
import org.gradle.configuration.InitScriptProcessor;
import org.gradle.configuration.ProjectsPreparer;
import org.gradle.configuration.ScriptPluginFactory;
import org.gradle.configuration.ScriptPluginFactorySelector;
import org.gradle.configuration.internal.UserCodeApplicationContext;
import org.gradle.configuration.project.DefaultCompileOperationFactory;
import org.gradle.configuration.project.PluginsProjectConfigureActions;
import org.gradle.execution.CompositeAwareTaskSelector;
import org.gradle.execution.ProjectConfigurer;
import org.gradle.execution.TaskNameResolver;
import org.gradle.execution.TaskPathProjectEvaluator;
import org.gradle.execution.TaskSelector;
import org.gradle.execution.plan.DefaultNodeValidator;
import org.gradle.execution.plan.ExecutionNodeAccessHierarchies;
import org.gradle.execution.plan.ExecutionPlanFactory;
import org.gradle.execution.plan.OrdinalGroupFactory;
import org.gradle.execution.plan.TaskDependencyResolver;
import org.gradle.execution.plan.TaskNodeDependencyResolver;
import org.gradle.execution.plan.TaskNodeFactory;
import org.gradle.execution.plan.WorkNodeDependencyResolver;
import org.gradle.groovy.scripts.DefaultScriptCompilerFactory;
import org.gradle.groovy.scripts.ScriptCompilerFactory;
import org.gradle.groovy.scripts.internal.DefaultScriptCompilationHandler;
import org.gradle.groovy.scripts.internal.DefaultScriptRunnerFactory;
import org.gradle.groovy.scripts.internal.FileCacheBackedScriptClassCompiler;
import org.gradle.groovy.scripts.internal.ScriptClassCompiler;
import org.gradle.groovy.scripts.internal.ScriptRunnerFactory;
import org.gradle.initialization.BuildLoader;
import org.gradle.initialization.ClassLoaderScopeRegistry;
import org.gradle.initialization.DefaultGradlePropertiesController;
import org.gradle.initialization.DefaultGradlePropertiesLoader;
import org.gradle.initialization.DefaultProjectDescriptorRegistry;
import org.gradle.initialization.DefaultSettingsLoaderFactory;
import org.gradle.initialization.DefaultSettingsPreparer;
import org.gradle.initialization.Environment;
import org.gradle.initialization.GradlePropertiesController;
import org.gradle.initialization.GradleUserHomeDirProvider;
import org.gradle.initialization.IGradlePropertiesLoader;
import org.gradle.initialization.InitScriptHandler;
import org.gradle.initialization.InstantiatingBuildLoader;
import org.gradle.initialization.ModelConfigurationListener;
import org.gradle.initialization.NotifyingBuildLoader;
import org.gradle.initialization.ProjectDescriptorRegistry;
import org.gradle.initialization.ProjectPropertySettingBuildLoader;
import org.gradle.initialization.ScriptEvaluatingSettingsProcessor;
import org.gradle.initialization.SettingsFactory;
import org.gradle.initialization.SettingsLoaderFactory;
import org.gradle.initialization.SettingsPreparer;
import org.gradle.initialization.SettingsProcessor;
import org.gradle.initialization.buildsrc.BuildSourceBuilder;
import org.gradle.initialization.buildsrc.BuildSrcBuildListenerFactory;
import org.gradle.initialization.buildsrc.BuildSrcProjectConfigurationAction;
import org.gradle.initialization.layout.BuildLayout;
import org.gradle.initialization.layout.BuildLayoutConfiguration;
import org.gradle.initialization.layout.BuildLayoutFactory;
import org.gradle.initialization.layout.ResolvedBuildLayout;
import org.gradle.internal.Cast;
import org.gradle.internal.authentication.AuthenticationSchemeRegistry;
import org.gradle.internal.authentication.DefaultAuthenticationSchemeRegistry;
import org.gradle.internal.build.BuildModelControllerServices;
import org.gradle.internal.build.BuildOperationFiringBuildWorkPreparer;
import org.gradle.internal.build.BuildState;
import org.gradle.internal.build.BuildStateRegistry;
import org.gradle.internal.build.BuildWorkPreparer;
import org.gradle.internal.build.DefaultBuildWorkGraphController;
import org.gradle.internal.build.DefaultBuildWorkPreparer;
import org.gradle.internal.build.DefaultPublicBuildPath;
import org.gradle.internal.build.PublicBuildPath;
import org.gradle.internal.buildtree.BuildInclusionCoordinator;
import org.gradle.internal.buildtree.BuildModelParameters;
import org.gradle.internal.classpath.CachedClasspathTransformer;
import org.gradle.internal.composite.DefaultBuildIncluder;
import org.gradle.internal.event.DefaultListenerManager;
import org.gradle.internal.event.ListenerManager;
import org.gradle.internal.file.Deleter;
import org.gradle.internal.file.FileException;
import org.gradle.internal.file.RelativeFilePathResolver;
import org.gradle.internal.hash.ClassLoaderHierarchyHasher;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.isolation.IsolatableFactory;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.progress.ProgressLoggerFactory;
import org.gradle.internal.nativeintegration.filesystem.FileSystem;
import org.gradle.internal.nativeintegration.services.FileSystems;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.operations.BuildOperationQueueFactory;
import org.gradle.internal.operations.DefaultBuildOperationQueueFactory;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.resource.UriTextResource;
import org.gradle.internal.resources.ResourceLockCoordinationService;
import org.gradle.internal.service.CachingServiceLocator;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.resource.StringTextResource;
import org.gradle.internal.resource.TextFileResourceLoader;
import org.gradle.internal.resource.local.FileResourceListener;
import org.gradle.internal.scripts.ScriptExecutionListener;
import org.gradle.internal.snapshot.CaseSensitivity;
import org.gradle.internal.work.WorkerLeaseService;
import org.gradle.model.internal.inspect.ModelRuleSourceDetector;
import org.gradle.plugin.management.internal.autoapply.AutoAppliedPluginHandler;
import org.gradle.plugin.use.internal.PluginRequestApplicator;
import org.gradle.tooling.provider.model.internal.BuildScopeToolingModelBuilderRegistryAction;
import org.gradle.tooling.provider.model.internal.DefaultToolingModelBuilderRegistry;
import org.gradle.util.GUtil;
import org.gradle.util.internal.GFileUtils;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

@SuppressWarnings({"unused"})
public class BuildScopeServices extends DefaultServiceRegistry {

    public BuildScopeServices(ServiceRegistry parent, BuildModelControllerServices.Supplier supplier) {
        super(parent);


        register(registration -> {
            registration.add(ProjectFactory.class);
            registration.add(DefaultNodeValidator.class);
            registration.add(TaskNodeFactory.class);
            registration.add(TaskNodeDependencyResolver.class);
            registration.add(DefaultFileOperations.class);
            registration.add(WorkNodeDependencyResolver.class);
            registration.add(TaskDependencyResolver.class);
            registration.add(DefaultBuildWorkGraphController.class);
            registration.add(TaskPathProjectEvaluator.class);

            registration.add(DefaultSettingsLoaderFactory.class);
            registration.add(ResolvedBuildLayout.class);
            registration.add(DefaultBuildIncluder.class);

            supplier.applyServicesTo(registration, this);
            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                pluginServiceRegistry.registerBuildServices(registration);
            }
        });
    }


    TextFileResourceLoader createTextFileResourceLoader(RelativeFilePathResolver relativeFilePathResolver) {
        return (description, sourceFile) -> {
            if (sourceFile == null) {
                return new StringTextResource(description, "");
            }
            if (sourceFile.exists()) {
                return new UriTextResource(description, sourceFile, relativeFilePathResolver);
            }
            return new StringTextResource(description, "");
        };
    }

    protected DefaultResourceHandler.Factory createResourceHandlerFactory(FileResolver fileResolver, FileSystem fileSystem, TemporaryFileProvider temporaryFileProvider, ApiTextResourceAdapter.Factory textResourceAdapterFactory) {
        return DefaultResourceHandler.Factory.from(
                fileResolver,
                fileSystem,
                temporaryFileProvider,
                textResourceAdapterFactory
        );
    }

    InitScriptHandler createInitScriptHandler(BuildOperationExecutor buildOperationExecutor, TextFileResourceLoader resourceLoader) {
        return new InitScriptHandler(new InitScriptProcessor() {
            @Override
            public void process(Object initScript, GradleInternal gradle) {

            }
        }, buildOperationExecutor, resourceLoader);
    }

    SettingsProcessor createSettingsProcessor(
            Instantiator instantiator,
            ScriptPluginFactory scriptPluginFactory,
            ScriptHandlerFactory scriptHandlerFactory,
            ServiceRegistryFactory serviceRegistryFactory,
            GradleProperties gradleProperties,
            BuildOperationExecutor buildOperationExecutor,
            TextFileResourceLoader textFileResourceLoader
    ) {
        return new ScriptEvaluatingSettingsProcessor(
                scriptPluginFactory,
                new SettingsFactory(
                        instantiator,
                        serviceRegistryFactory,
                        scriptHandlerFactory
                ),
                gradleProperties,
                textFileResourceLoader
        );
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

    protected DefaultProjectRegistry<ProjectInternal> createProjectRegistry() {
            return new DefaultProjectRegistry<>();
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

    protected PluginRegistry createPluginRegistry(ClassLoaderScopeRegistry scopeRegistry, PluginInspector pluginInspector) {
        return new DefaultPluginRegistry(pluginInspector, scopeRegistry.getCoreAndPluginsScope());
    }

    protected TaskSelector createTaskSelector(GradleInternal gradle, BuildStateRegistry buildStateRegistry, ProjectConfigurer projectConfigurer) {
        return new CompositeAwareTaskSelector(gradle, buildStateRegistry, projectConfigurer, new TaskNameResolver());
    }

    protected ScriptClassPathResolver createScriptClassPathResolver(List<ScriptClassPathInitializer> initializers) {
        return new DefaultScriptClassPathResolver(initializers);
    }

    protected ScriptHandlerFactory createScriptHandlerFactory(DependencyManagementServices dependencyManagementServices, FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, DependencyMetaDataProvider dependencyMetaDataProvider, ScriptClassPathResolver classPathResolver, NamedObjectInstantiator instantiator) {
        return new DefaultScriptHandlerFactory(
                dependencyManagementServices,
                fileResolver,
                fileCollectionFactory,
                dependencyMetaDataProvider,
                classPathResolver,
                instantiator);
    }

    protected ProjectTaskLister createProjectTaskLister() {
        return new DefaultProjectTaskLister();
    }


    protected ComponentTypeRegistry createComponentTypeRegistry() {
        return new DefaultComponentTypeRegistry();
    }

    private static class DependencyMetaDataProviderImpl implements DependencyMetaDataProvider {
        @Override
        public Module getModule() {
            return new DefaultModule("unspecified", "unspecified", Project.DEFAULT_VERSION, Project.DEFAULT_STATUS);
        }
    }


    protected DependencyMetaDataProvider createDependencyMetaDataProvider() {
        return new DependencyMetaDataProviderImpl();
    }


    protected PluginInspector createPluginInspector(ModelRuleSourceDetector modelRuleSourceDetector) {
        return new PluginInspector(modelRuleSourceDetector);
    }

    protected ProjectsPreparer createBuildConfigurer(
            ProjectConfigurer projectConfigurer,
            BuildSourceBuilder buildSourceBuilder,
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
                        inclusionCoordinator,
                        buildSourceBuilder),
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

    protected DefaultScriptCompilationHandler createScriptCompilationHandler(Deleter deleter, ImportsReader importsReader) {
        return new DefaultScriptCompilationHandler(deleter, importsReader);
    }


    protected FileCacheBackedScriptClassCompiler createFileCacheBackedScriptClassCompiler(
            BuildOperationExecutor buildOperationExecutor,
            GlobalScopedCache cacheRepository,
            ClassLoaderHierarchyHasher classLoaderHierarchyHasher,
            DefaultScriptCompilationHandler scriptCompilationHandler,
            CachedClasspathTransformer classpathTransformer,
            ProgressLoggerFactory progressLoggerFactory
    ) {
        return new FileCacheBackedScriptClassCompiler(
                cacheRepository,
//                new BuildOperationBackedScriptCompilationHandler(scriptCompilationHandler, buildOperationExecutor),
                scriptCompilationHandler,
                progressLoggerFactory,
                classLoaderHierarchyHasher,
                classpathTransformer);
    }

    protected ITaskFactory createITaskFactory(Instantiator instantiator, TaskClassInfoStore taskClassInfoStore) {
        return new AnnotationProcessingTaskFactory(
                instantiator,
                taskClassInfoStore,
                new TaskFactory());
    }



    protected ScriptCompilerFactory createScriptCompileFactory(
            ScriptClassCompiler scriptClassCompiler,
            ScriptRunnerFactory scriptRunnerFactory
    ) {
        return new DefaultScriptCompilerFactory(scriptClassCompiler, scriptRunnerFactory);
    }
    protected ScriptPluginFactory createScriptPluginFactory(
            InstantiatorFactory instantiatorFactory,
            BuildOperationExecutor buildOperationExecutor,
            UserCodeApplicationContext userCodeApplicationContext
    ) {
        DefaultScriptPluginFactory defaultScriptPluginFactory = defaultScriptPluginFactory();
        ScriptPluginFactorySelector.ProviderInstantiator instantiator = ScriptPluginFactorySelector.defaultProviderInstantiatorFor(instantiatorFactory.inject(this));
        ScriptPluginFactorySelector scriptPluginFactorySelector = new ScriptPluginFactorySelector(defaultScriptPluginFactory, instantiator, buildOperationExecutor, userCodeApplicationContext);
        defaultScriptPluginFactory.setScriptPluginFactory(scriptPluginFactorySelector);
        return scriptPluginFactorySelector;
    }

    protected BuildSourceBuilder createBuildSourceBuilder(BuildState currentBuild, FileLockManager fileLockManager, BuildOperationExecutor buildOperationExecutor, CachedClasspathTransformer cachedClasspathTransformer, CachingServiceLocator cachingServiceLocator, BuildStateRegistry buildRegistry, PublicBuildPath publicBuildPath) {
        return new BuildSourceBuilder(
                currentBuild,
                fileLockManager,
                buildOperationExecutor,
                cachedClasspathTransformer,
                new BuildSrcBuildListenerFactory(
                        PluginsProjectConfigureActions.of(
                                BuildSrcProjectConfigurationAction.class,
                                cachingServiceLocator)),
                buildRegistry,
                publicBuildPath);
    }

    protected ScriptRunnerFactory createScriptRunnerFactory(ListenerManager listenerManager, InstantiatorFactory instantiatorFactory) {
        ScriptExecutionListener scriptExecutionListener = listenerManager.getBroadcaster(ScriptExecutionListener.class);
        return new DefaultScriptRunnerFactory(
                scriptExecutionListener,
                instantiatorFactory.inject()
        );
    }

    private DefaultScriptPluginFactory defaultScriptPluginFactory() {
        return new DefaultScriptPluginFactory(
                this,
                get(ScriptCompilerFactory.class),
                getFactory(LoggingManagerInternal.class),
                get(AutoAppliedPluginHandler.class),
                get(PluginRequestApplicator.class),
                get(CompileOperationFactory.class));
    }

    public CompileOperationFactory createCompileOperationFactory(DocumentationRegistry documentationRegistry) {
        return new DefaultCompileOperationFactory(documentationRegistry);
    }

    Environment createEnvironment() {
        return new Environment() {
            @Nullable
            @Override
            public Map<String, String> propertiesFile(File propertiesFile) {
                if (propertiesFile.isFile()) {
                    return Cast.uncheckedCast(GUtil.loadProperties(propertiesFile));
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

    protected ValueSourceProviderFactory createValueSourceProviderFactory(
            InstantiatorFactory instantiatorFactory,
            IsolatableFactory isolatableFactory,
            ServiceRegistry services,
            GradleProperties gradleProperties,
            ListenerManager listenerManager
    ) {
        return new DefaultValueSourceProviderFactory(
                listenerManager,
                instantiatorFactory,
                isolatableFactory,
                gradleProperties,
                services
        );
    }

    protected ProviderFactory createProviderFactory(
            Instantiator instantiator,
            ValueSourceProviderFactory valueSourceProviderFactory,
            ListenerManager listenerManager
    ) {
        return instantiator.newInstance(DefaultProviderFactory.class, valueSourceProviderFactory, listenerManager);
    }

    AuthenticationSchemeRegistry createAuthenticationSchemeRegistry() {
        return new DefaultAuthenticationSchemeRegistry();
    }

    protected DefaultToolingModelBuilderRegistry createBuildScopedToolingModelBuilders(
            List<BuildScopeToolingModelBuilderRegistryAction> registryActions,
            BuildOperationExecutor buildOperationExecutor,
            ProjectStateRegistry projectStateRegistry,
            UserCodeApplicationContext userCodeApplicationContext
    ) {
        DefaultToolingModelBuilderRegistry registry = new DefaultToolingModelBuilderRegistry(buildOperationExecutor, projectStateRegistry, userCodeApplicationContext);
        // Services are created on demand, and this may happen while applying a plugin
        userCodeApplicationContext.gradleRuntime(() -> {
            for (BuildScopeToolingModelBuilderRegistryAction registryAction : registryActions) {
                registryAction.execute(registry);
            }
        });
        return registry;
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

    OrdinalGroupFactory createOrdinalGroupFactory() {
        return new OrdinalGroupFactory();
    }

    ExecutionPlanFactory createExecutionPlanFactory(
            GradleInternal gradleInternal,
            TaskNodeFactory taskNodeFactory,
            OrdinalGroupFactory ordinalGroupFactory,
            TaskDependencyResolver dependencyResolver,
            ExecutionNodeAccessHierarchies executionNodeAccessHierarchies,
            ResourceLockCoordinationService lockCoordinationService
    ) {
        return new ExecutionPlanFactory(
                gradleInternal.getIdentityPath().toString(),
                taskNodeFactory,
                ordinalGroupFactory,
                dependencyResolver,
                executionNodeAccessHierarchies.getOutputHierarchy(),
                executionNodeAccessHierarchies.getDestroyableHierarchy(),
                lockCoordinationService
        );
    }
    ExecutionNodeAccessHierarchies createExecutionNodeAccessHierarchies() {
        return new ExecutionNodeAccessHierarchies(CaseSensitivity.CASE_INSENSITIVE, FileSystems.getDefault());
    }

    protected BuildScopedCache createBuildScopedCache(
            GradleUserHomeDirProvider userHomeDirProvider,
            BuildLayout buildLayout,
            StartParameter startParameter,
            CacheRepository cacheRepository
    ) {
        BuildScopeCacheDir cacheDir = new BuildScopeCacheDir(userHomeDirProvider, buildLayout, startParameter);
        return new DefaultBuildScopedCache(cacheDir.getDir(), cacheRepository);
    }


    protected BuildLayout createBuildLayout(BuildLayoutFactory buildLayoutFactory, BuildDefinition buildDefinition) {
        return buildLayoutFactory.getLayoutFor(new BuildLayoutConfiguration(buildDefinition.getStartParameter()));
    }

    protected PublicBuildPath createPublicBuildPath(BuildState buildState) {
        return new DefaultPublicBuildPath(buildState.getIdentityPath());
    }

    protected TaskStatistics createTaskStatistics() {
        return new TaskStatistics();
    }

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
