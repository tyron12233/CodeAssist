package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.Plugin;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.MutationGuards;
import com.tyron.builder.api.internal.artifacts.DependencyManagementServices;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.collections.DefaultDomainObjectCollectionFactory;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
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
import com.tyron.builder.api.internal.project.CrossProjectConfigurator;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.project.ProjectStateUnk;
import com.tyron.builder.api.internal.resources.ApiTextResourceAdapter;
import com.tyron.builder.api.internal.resources.DefaultResourceHandler;
import com.tyron.builder.api.internal.tasks.DefaultTaskContainer;
import com.tyron.builder.api.internal.tasks.DefaultTaskDependencyFactory;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.internal.tasks.TaskDependencyFactory;
import com.tyron.builder.configuration.ConfigurationTargetIdentifier;
import com.tyron.builder.configuration.internal.UserCodeApplicationContext;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.instantiation.InstantiatorFactory;
import com.tyron.builder.internal.model.ModelContainer;
import com.tyron.builder.internal.nativeintegration.filesystem.FileSystem;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.reflect.DirectInstantiator;
import com.tyron.builder.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.internal.resource.TextUriResourceLoader;
import com.tyron.builder.util.Path;

import java.io.File;

import javax.annotation.Nullable;

public class ProjectScopeServices extends DefaultServiceRegistry {

    private final ProjectInternal project;

    public ProjectScopeServices(final ServiceRegistry parent, final ProjectInternal project) {
        super(parent);
        this.project = project;
//        this.loggingManagerInternalFactory = loggingManagerInternalFactory;
        register(registration -> {
            registration.add(ProjectInternal.class, project);
//            parent.get(DependencyManagementServices.class).addDslServices(registration, project);
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

    // TODO: move this to DependencyManagementServices
    protected ApiTextResourceAdapter.Factory createTextResourceAdapterFactory(
            TextUriResourceLoader.Factory textUriResourceLoaderFactory, TemporaryFileProvider tempFileProvider) {
        return new ApiTextResourceAdapter.Factory(textUriResourceLoaderFactory, tempFileProvider);
    }


    protected TaskContainerInternal createTaskContainerInternal(
            BuildOperationExecutor buildOperationExecutor,
            CrossProjectConfigurator crossProjectConfigurator,
            CollectionCallbackActionDecorator collectionCallbackActionDecorator
    ) {
        return new DefaultTaskContainer(
                project,
                buildOperationExecutor,
                crossProjectConfigurator,
                collectionCallbackActionDecorator
        );
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

    protected PluginManagerInternal createPluginManager(
            PluginRegistry pluginRegistry,
            BuildOperationExecutor buildOperationExecutor,
            UserCodeApplicationContext userCodeApplicationContext,
            CollectionCallbackActionDecorator collectionCallbackActionDecorator,
            DomainObjectCollectionFactory domainObjectCollectionFactory
    ) {
        PluginTarget target = new PluginTarget() {
            @Override
            public ConfigurationTargetIdentifier getConfigurationTargetIdentifier() {
                return ConfigurationTargetIdentifier.of(project);
            }

            @Override
            public void applyImperative(@Nullable String pluginId, Plugin<?> plugin) {
                Plugin<ProjectInternal> internalPlugin = Cast.uncheckedCast(plugin);
                internalPlugin.apply(project);
            }

            @Override
            public void applyRules(@Nullable String pluginId, Class<?> clazz) {

            }

            @Override
            public void applyImperativeRulesHybrid(@Nullable String pluginId, Plugin<?> plugin) {

            }
        };
        return new DefaultPluginManager(
                pluginRegistry,
                DirectInstantiator.INSTANCE,
                target,
                buildOperationExecutor,
                userCodeApplicationContext,
                collectionCallbackActionDecorator,
                domainObjectCollectionFactory
        );
    }

    protected DomainObjectCollectionFactory createDomainObjectCollectionFactory(InstantiatorFactory instantiatorFactory, CollectionCallbackActionDecorator collectionCallbackActionDecorator, CrossProjectConfigurator projectConfigurator) {
        ServiceRegistry services = ProjectScopeServices.this;
        return new DefaultDomainObjectCollectionFactory(instantiatorFactory, services, collectionCallbackActionDecorator, MutationGuards.of(projectConfigurator));
    }

    protected TemporaryFileProvider createTemporaryFileProvider() {
        return new DefaultTemporaryFileProvider(() -> new File(project.getBuildDir(), "tmp"));
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
