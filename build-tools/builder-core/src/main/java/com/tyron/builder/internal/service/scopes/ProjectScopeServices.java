package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.component.SoftwareComponentContainer;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.MutationGuards;
import com.tyron.builder.api.internal.artifacts.DependencyManagementServices;
import com.tyron.builder.api.internal.artifacts.Module;
import com.tyron.builder.api.internal.artifacts.ProjectBackedModule;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.artifacts.dsl.dependencies.ProjectFinder;
import com.tyron.builder.api.internal.collections.DefaultDomainObjectCollectionFactory;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
import com.tyron.builder.api.internal.component.ComponentRegistry;
import com.tyron.builder.api.internal.component.DefaultSoftwareComponentContainer;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.temp.DefaultTemporaryFileProvider;
import com.tyron.builder.api.internal.file.temp.TemporaryFileProvider;
import com.tyron.builder.api.internal.initialization.DefaultScriptHandlerFactory;
import com.tyron.builder.api.internal.initialization.ScriptClassPathResolver;
import com.tyron.builder.api.internal.initialization.ScriptHandlerFactory;
import com.tyron.builder.api.internal.initialization.ScriptHandlerInternal;
import com.tyron.builder.api.internal.model.NamedObjectInstantiator;
import com.tyron.builder.api.internal.plugins.DefaultPluginManager;
import com.tyron.builder.api.internal.plugins.PluginManagerInternal;
import com.tyron.builder.api.internal.plugins.PluginRegistry;
import com.tyron.builder.api.internal.plugins.PluginTarget;
import com.tyron.builder.api.internal.plugins.RuleBasedPluginTarget;
import com.tyron.builder.api.internal.project.CrossProjectConfigurator;
import com.tyron.builder.api.internal.project.DeferredProjectConfiguration;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.api.internal.project.taskfactory.ITaskFactory;
import com.tyron.builder.api.internal.project.taskfactory.TaskInstantiator;
import com.tyron.builder.api.internal.resources.ApiTextResourceAdapter;
import com.tyron.builder.api.internal.resources.DefaultResourceHandler;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainerFactory;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskStatistics;
import com.tyron.builder.api.internal.tasks.properties.TaskScheme;
import com.tyron.builder.configuration.ConfigurationTargetIdentifier;
import com.tyron.builder.configuration.internal.UserCodeApplicationContext;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.logging.LoggingManagerInternal;
import com.tyron.builder.internal.model.ModelContainer;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.service.DefaultServiceRegistry;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.internal.resource.TextUriResourceLoader;
import com.tyron.builder.model.internal.inspect.ModelRuleExtractor;
import com.tyron.builder.model.internal.inspect.ModelRuleSourceDetector;
import com.tyron.builder.model.internal.registry.DefaultModelRegistry;
import com.tyron.builder.model.internal.registry.ModelRegistry;
import com.tyron.builder.util.Path;

import java.io.File;

import javax.annotation.Nullable;

public class ProjectScopeServices extends DefaultServiceRegistry {

    private final ProjectInternal project;
    private final Factory<LoggingManagerInternal> loggingManagerInternalFactory;

    public ProjectScopeServices(final ServiceRegistry parent,
                                final ProjectInternal project,
                                Factory<LoggingManagerInternal> loggingManagerInternalFactory) {
        super(parent);
        this.project = project;
        this.loggingManagerInternalFactory = loggingManagerInternalFactory;
        register(registration -> {
            registration.add(ProjectInternal.class, project);
            parent.get(DependencyManagementServices.class).addDslServices(registration, project);
            for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                pluginServiceRegistry.registerProjectServices(registration);
            }
        });
        addProvider(new WorkerSharedProjectScopeServices(project.getProjectDir()));
    }

    protected DefaultResourceHandler.Factory createResourceHandlerFactory(FileResolver fileResolver, FileSystem fileSystem, TemporaryFileProvider temporaryFileProvider, ApiTextResourceAdapter.Factory textResourceAdapterFactory) {
        return DefaultResourceHandler.Factory.from(
                fileResolver,
                fileSystem,
                temporaryFileProvider,
                textResourceAdapterFactory
        );
    }

    protected LoggingManagerInternal createLoggingManager() {
        return loggingManagerInternalFactory.create();
    }

    // TODO: move this to DependencyManagementServices
    protected ApiTextResourceAdapter.Factory createTextResourceAdapterFactory(
            TextUriResourceLoader.Factory textUriResourceLoaderFactory, TemporaryFileProvider tempFileProvider) {
        return new ApiTextResourceAdapter.Factory(textUriResourceLoaderFactory, tempFileProvider);
    }


    protected TaskContainerInternal createTaskContainerInternal(TaskStatistics taskStatistics, BuildOperationExecutor buildOperationExecutor, CrossProjectConfigurator crossProjectConfigurator, CollectionCallbackActionDecorator decorator) {
        return new DefaultTaskContainerFactory(
                get(Instantiator.class),
                get(ITaskFactory.class),
                project,
                taskStatistics,
                buildOperationExecutor,
                crossProjectConfigurator,
                decorator
        ).create();
    }

    protected SoftwareComponentContainer createSoftwareComponentContainer(CollectionCallbackActionDecorator decorator) {
        Instantiator instantiator = get(Instantiator.class);
        return instantiator.newInstance(DefaultSoftwareComponentContainer.class, instantiator, decorator);
    }


    protected TaskDependencyFactory createTaskDependencyFactory() {
        return DefaultTaskDependencyFactory.forProject(project.getTasks());
    }

    protected ConfigurationTargetIdentifier createConfigurationTargetIdentifier() {
        return ConfigurationTargetIdentifier.of(project);
    }

    protected PluginRegistry createPluginRegistry(PluginRegistry rootRegistry) {
        PluginRegistry parentRegistry;
        ProjectStateUnk parent = project.getOwner().getBuildParent();
        if (parent == null) {
            parentRegistry = rootRegistry.createChild(project.getBaseClassLoaderScope());
        } else {
            parentRegistry = parent.getMutableModel().getServices().get(PluginRegistry.class);
        }
        return parentRegistry.createChild(project.getClassLoaderScope());
    }


    protected DeferredProjectConfiguration createDeferredProjectConfiguration() {
        return new DeferredProjectConfiguration(project);
    }


    protected PluginManagerInternal createPluginManager(Instantiator instantiator, InstantiatorFactory instantiatorFactory, BuildOperationExecutor buildOperationExecutor, UserCodeApplicationContext userCodeApplicationContext, CollectionCallbackActionDecorator decorator, DomainObjectCollectionFactory domainObjectCollectionFactory) {
        PluginTarget target = new RuleBasedPluginTarget(
                project,
                get(ModelRuleExtractor.class),
                get(ModelRuleSourceDetector.class)
        );
        return instantiator.newInstance(DefaultPluginManager.class, get(PluginRegistry.class), instantiatorFactory.inject(this), target, buildOperationExecutor, userCodeApplicationContext, decorator, domainObjectCollectionFactory);
    }


    protected ITaskFactory createTaskFactory(ITaskFactory parentFactory, TaskScheme taskScheme) {
        return parentFactory.createChild(project, taskScheme.getInstantiationScheme().withServices(this));
    }

    protected TaskInstantiator createTaskInstantiator(ITaskFactory taskFactory) {
        return new TaskInstantiator(taskFactory, project);
    }

    protected DomainObjectCollectionFactory createDomainObjectCollectionFactory(InstantiatorFactory instantiatorFactory, CollectionCallbackActionDecorator collectionCallbackActionDecorator, CrossProjectConfigurator projectConfigurator) {
        ServiceRegistry services = ProjectScopeServices.this;
        return new DefaultDomainObjectCollectionFactory(instantiatorFactory, services, collectionCallbackActionDecorator, MutationGuards.of(projectConfigurator));
    }

    protected TemporaryFileProvider createTemporaryFileProvider() {
        return new DefaultTemporaryFileProvider(() -> new File(project.getBuildDir(), "tmp"));
    }

    protected ProjectFinder createProjectFinder() {
        return new DefaultProjectFinder(() -> project);
    }

    protected DependencyMetaDataProvider createDependencyMetaDataProvider() {
        return new ProjectBackedModuleMetaDataProvider();
    }

    private class ProjectBackedModuleMetaDataProvider implements DependencyMetaDataProvider {
        @Override
        public Module getModule() {
            return new ProjectBackedModule(project);
        }
    }

    protected ComponentRegistry createComponentRegistry() {
        return new ComponentRegistry();
    }

    protected ModelRegistry createModelRegistry(ModelRuleExtractor ruleExtractor) {
        return new DefaultModelRegistry(ruleExtractor, project.getPath(), run -> project.getOwner().applyToMutableState(p -> run.run()));
    }

    protected ScriptHandlerInternal createScriptHandler(DependencyManagementServices dependencyManagementServices, FileResolver fileResolver, FileCollectionFactory fileCollectionFactory, DependencyMetaDataProvider dependencyMetaDataProvider, ScriptClassPathResolver scriptClassPathResolver, NamedObjectInstantiator instantiator) {
        ScriptHandlerFactory factory = new DefaultScriptHandlerFactory(
                dependencyManagementServices,
                fileResolver,
                fileCollectionFactory,
                dependencyMetaDataProvider,
                scriptClassPathResolver,
                instantiator);
        return factory.create(project.getBuildScriptSource(), project.getClassLoaderScope(), new ScriptScopedContext(project));
    }

    private static class ScriptScopedContext implements DomainObjectContext {
        private final DomainObjectContext delegate;

        public ScriptScopedContext(DomainObjectContext delegate) {
            this.delegate = delegate;
        }

        @Override
        public Path identityPath(String name) {
            return delegate.identityPath(name);
        }

        @Override
        public Path projectPath(String name) {
            return delegate.projectPath(name);
        }

        @Override
        public Path getProjectPath() {
            return delegate.getProjectPath();
        }

        @Nullable
        @Override
        public ProjectInternal getProject() {
            return delegate.getProject();
        }

        @Override
        public ModelContainer<?> getModel() {
            return delegate.getModel();
        }

        @Override
        public Path getBuildPath() {
            return delegate.getBuildPath();
        }

        @Override
        public boolean isScript() {
            return true;
        }

        @Override
        public boolean isRootScript() {
            return false;
        }

        @Override
        public boolean isPluginContext() {
            return false;
        }
    }
}
