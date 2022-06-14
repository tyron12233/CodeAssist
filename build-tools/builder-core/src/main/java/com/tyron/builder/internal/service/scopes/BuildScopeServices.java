package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.StartParameter;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.artifacts.ConfigurationContainer;
import com.tyron.builder.api.artifacts.dsl.DependencyHandler;
import com.tyron.builder.api.artifacts.dsl.DependencyLockingHandler;
import com.tyron.builder.api.artifacts.dsl.RepositoryHandler;
import com.tyron.builder.api.attributes.AttributesSchema;
import com.tyron.builder.api.internal.BuildDefinition;
import com.tyron.builder.api.internal.DocumentationRegistry;
import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.StartParameterInternal;
import com.tyron.builder.api.internal.artifacts.DefaultModule;
import com.tyron.builder.api.internal.artifacts.DependencyManagementServices;
import com.tyron.builder.api.internal.artifacts.DependencyResolutionServices;
import com.tyron.builder.api.internal.artifacts.Module;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import com.tyron.builder.api.internal.attributes.ImmutableAttributesFactory;
import com.tyron.builder.api.internal.classpath.DefaultModuleRegistry;
import com.tyron.builder.api.internal.component.ComponentTypeRegistry;
import com.tyron.builder.api.internal.component.DefaultComponentTypeRegistry;
import com.tyron.builder.api.internal.file.DefaultFileOperations;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.initialization.DefaultScriptClassPathResolver;
import com.tyron.builder.api.internal.initialization.DefaultScriptHandlerFactory;
import com.tyron.builder.api.internal.initialization.ScriptClassPathInitializer;
import com.tyron.builder.api.internal.initialization.ScriptClassPathResolver;
import com.tyron.builder.api.internal.initialization.ScriptHandlerFactory;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.internal.plugins.DefaultPluginRegistry;
import com.tyron.builder.api.internal.plugins.PluginInspector;
import com.tyron.builder.api.internal.plugins.PluginRegistry;
import com.tyron.builder.api.internal.project.DefaultProjectRegistry;
import com.tyron.builder.api.internal.project.DefaultProjectTaskLister;
import com.tyron.builder.api.internal.project.ProjectFactory;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectTaskLister;
import com.tyron.builder.api.internal.project.taskfactory.AnnotationProcessingTaskFactory;
import com.tyron.builder.api.internal.project.taskfactory.ITaskFactory;
import com.tyron.builder.api.internal.project.taskfactory.TaskClassInfoStore;
import com.tyron.builder.api.internal.project.taskfactory.TaskFactory;
import com.tyron.builder.api.internal.properties.GradleProperties;
import com.tyron.builder.api.internal.provider.DefaultProviderFactory;
import com.tyron.builder.api.internal.provider.DefaultValueSourceProviderFactory;
import com.tyron.builder.api.internal.provider.ValueSourceProviderFactory;
import com.tyron.builder.api.internal.resources.ApiTextResourceAdapter;
import com.tyron.builder.api.internal.resources.DefaultResourceHandler;
import com.tyron.builder.api.internal.tasks.TaskStatistics;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ProviderFactory;
import com.tyron.builder.cache.CacheRepository;
import com.tyron.builder.cache.internal.BuildScopeCacheDir;
import com.tyron.builder.cache.internal.scopes.DefaultBuildScopedCache;
import com.tyron.builder.cache.scopes.BuildScopedCache;
import com.tyron.builder.cache.scopes.GlobalScopedCache;
import com.tyron.builder.caching.internal.packaging.impl.FilePermissionAccess;
import com.tyron.builder.configuration.BuildOperationFiringProjectsPreparer;
import com.tyron.builder.configuration.BuildTreePreparingProjectsPreparer;
import com.tyron.builder.configuration.CompileOperationFactory;
import com.tyron.builder.configuration.DefaultProjectsPreparer;
import com.tyron.builder.configuration.DefaultScriptPluginFactory;
import com.tyron.builder.configuration.ImportsReader;
import com.tyron.builder.configuration.InitScriptProcessor;
import com.tyron.builder.configuration.ProjectsPreparer;
import com.tyron.builder.configuration.ScriptPluginFactory;
import com.tyron.builder.configuration.ScriptPluginFactorySelector;
import com.tyron.builder.configuration.internal.UserCodeApplicationContext;
import com.tyron.builder.configuration.project.DefaultCompileOperationFactory;
import com.tyron.builder.execution.CompositeAwareTaskSelector;
import com.tyron.builder.execution.ProjectConfigurer;
import com.tyron.builder.execution.TaskNameResolver;
import com.tyron.builder.execution.TaskPathProjectEvaluator;
import com.tyron.builder.execution.TaskSelector;
import com.tyron.builder.execution.plan.DefaultNodeValidator;
import com.tyron.builder.execution.plan.ExecutionNodeAccessHierarchies;
import com.tyron.builder.execution.plan.ExecutionPlanFactory;
import com.tyron.builder.execution.plan.TaskDependencyResolver;
import com.tyron.builder.execution.plan.TaskNodeDependencyResolver;
import com.tyron.builder.execution.plan.TaskNodeFactory;
import com.tyron.builder.groovy.scripts.DefaultScriptCompilerFactory;
import com.tyron.builder.groovy.scripts.ScriptCompilerFactory;
import com.tyron.builder.groovy.scripts.internal.DefaultScriptCompilationHandler;
import com.tyron.builder.groovy.scripts.internal.DefaultScriptRunnerFactory;
import com.tyron.builder.groovy.scripts.internal.FileCacheBackedScriptClassCompiler;
import com.tyron.builder.groovy.scripts.internal.ScriptClassCompiler;
import com.tyron.builder.groovy.scripts.internal.ScriptCompilationHandler;
import com.tyron.builder.groovy.scripts.internal.ScriptRunnerFactory;
import com.tyron.builder.initialization.BuildLoader;
import com.tyron.builder.initialization.ClassLoaderScopeRegistry;
import com.tyron.builder.initialization.DefaultGradlePropertiesController;
import com.tyron.builder.initialization.DefaultGradlePropertiesLoader;
import com.tyron.builder.initialization.DefaultProjectDescriptorRegistry;
import com.tyron.builder.initialization.DefaultSettingsLoaderFactory;
import com.tyron.builder.initialization.DefaultSettingsPreparer;
import com.tyron.builder.initialization.Environment;
import com.tyron.builder.initialization.GradlePropertiesController;
import com.tyron.builder.initialization.GradleUserHomeDirProvider;
import com.tyron.builder.initialization.IGradlePropertiesLoader;
import com.tyron.builder.initialization.InitScriptHandler;
import com.tyron.builder.initialization.InstantiatingBuildLoader;
import com.tyron.builder.initialization.ModelConfigurationListener;
import com.tyron.builder.initialization.NotifyingBuildLoader;
import com.tyron.builder.initialization.ProjectDescriptorRegistry;
import com.tyron.builder.initialization.ProjectPropertySettingBuildLoader;
import com.tyron.builder.initialization.ScriptEvaluatingSettingsProcessor;
import com.tyron.builder.initialization.SettingsFactory;
import com.tyron.builder.initialization.SettingsLoaderFactory;
import com.tyron.builder.initialization.SettingsPreparer;
import com.tyron.builder.initialization.SettingsProcessor;
import com.tyron.builder.initialization.layout.BuildLayout;
import com.tyron.builder.initialization.layout.BuildLayoutConfiguration;
import com.tyron.builder.initialization.layout.BuildLayoutFactory;
import com.tyron.builder.initialization.layout.ResolvedBuildLayout;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.authentication.AuthenticationSchemeRegistry;
import com.tyron.builder.internal.authentication.DefaultAuthenticationSchemeRegistry;
import com.tyron.builder.internal.build.BuildModelControllerServices;
import com.tyron.builder.internal.build.BuildOperationFiringBuildWorkPreparer;
import com.tyron.builder.internal.build.BuildState;
import com.tyron.builder.internal.build.BuildStateRegistry;
import com.tyron.builder.internal.build.BuildWorkPreparer;
import com.tyron.builder.internal.build.DefaultBuildWorkGraphController;
import com.tyron.builder.internal.build.DefaultBuildWorkPreparer;
import com.tyron.builder.internal.build.DefaultPublicBuildPath;
import com.tyron.builder.internal.build.PublicBuildPath;
import com.tyron.builder.internal.buildtree.BuildInclusionCoordinator;
import com.tyron.builder.internal.buildtree.BuildModelParameters;
import com.tyron.builder.internal.classpath.CachedClasspathTransformer;
import com.tyron.builder.internal.composite.DefaultBuildIncluder;
import com.tyron.builder.internal.event.DefaultListenerManager;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.file.FileException;
import com.tyron.builder.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.isolation.IsolatableFactory;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.logging.progress.ProgressLoggerFactory;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.nativeintegration.services.FileSystems;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.BuildOperationQueueFactory;
import com.tyron.builder.internal.operations.DefaultBuildOperationQueueFactory;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistration;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.resource.StringTextResource;
import com.tyron.builder.internal.resource.TextFileResourceLoader;
import com.tyron.builder.internal.resource.TextResource;
import com.tyron.builder.internal.resource.local.FileResourceListener;
import com.tyron.builder.internal.resources.ResourceLockCoordinationService;
import com.tyron.builder.internal.scripts.ScriptExecutionListener;
import com.tyron.builder.internal.snapshot.CaseSensitivity;
import com.tyron.builder.internal.work.WorkerLeaseService;
import com.tyron.builder.model.internal.inspect.ModelRuleSourceDetector;
import com.tyron.builder.plugin.management.internal.autoapply.AutoAppliedPluginHandler;
import com.tyron.builder.plugin.use.internal.PluginRequestApplicator;
import com.tyron.builder.util.GUtil;
import com.tyron.builder.util.internal.GFileUtils;

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
//            registration.add(WorkNodeDependencyResolver.class);
            registration.add(TaskDependencyResolver.class);
            registration.add(DefaultBuildWorkGraphController.class);
            registration.add(TaskPathProjectEvaluator.class);

//            registration.add(DefaultResourceLockCoordinationService.class);
            registration.add(DefaultSettingsLoaderFactory.class);
            registration.add(ResolvedBuildLayout.class);
            registration.add(DefaultBuildIncluder.class);

            supplier.applyServicesTo(registration, this);
            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                pluginServiceRegistry.registerBuildServices(registration);
            }
        });
    }


    TextFileResourceLoader createTextFileResourceLoader() {
        return (description, sourceFile) -> new StringTextResource(description, GFileUtils.readFileToString(sourceFile));
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
            return new DefaultModule("unspecified", "unspecified", BuildProject.DEFAULT_VERSION, BuildProject.DEFAULT_STATUS);
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
            ExecutionNodeAccessHierarchies executionNodeAccessHierarchies
    ) {
        return new ExecutionPlanFactory(
                gradleInternal.getIdentityPath().toString(),
                taskNodeFactory,
                dependencyResolver,
                new DefaultNodeValidator(),
                executionNodeAccessHierarchies.getOutputHierarchy(),
                executionNodeAccessHierarchies.getDestroyableHierarchy()
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
