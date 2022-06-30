package com.tyron.builder.api.internal.project;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.ProjectEvaluationListener;
import com.tyron.builder.api.ProjectState;
import com.tyron.builder.api.UnknownProjectException;
import com.tyron.builder.api.artifacts.dsl.DependencyHandler;
import com.tyron.builder.api.attributes.Attribute;
import com.tyron.builder.api.internal.DomainObjectContext;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.file.HasScriptServices;
import com.tyron.builder.api.internal.initialization.ClassLoaderScope;
import com.tyron.builder.api.internal.plugins.ExtensionContainerInternal;
import com.tyron.builder.api.internal.plugins.PluginAwareInternal;
import com.tyron.builder.api.internal.plugins.PluginManagerInternal;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.groovy.scripts.ScriptSource;
import com.tyron.builder.internal.metaobject.DynamicObject;
import com.tyron.builder.internal.model.RuleBasedPluginListener;
import com.tyron.builder.internal.service.ServiceRegistry;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.model.internal.registry.ModelRegistry;
import com.tyron.builder.model.internal.registry.ModelRegistryScope;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface ProjectInternal extends BuildProject, ProjectIdentifier, HasScriptServices, DomainObjectContext, ModelRegistryScope, PluginAwareInternal {

    // These constants are defined here and not with the rest of their kind in HelpTasksPlugin because they are referenced
    // in the ‘core’ modules, which don't depend on ‘plugins’ where HelpTasksPlugin is defined.
    String HELP_TASK = "help";
    String TASKS_TASK = "tasks";
    String PROJECTS_TASK = "projects";

    Attribute<String> STATUS_ATTRIBUTE = Attribute.of("org.gradle.status", String.class);

    @Nullable
    @Override
    ProjectInternal getParent();

    @Nullable
    ProjectInternal getParent(ProjectInternal referrer);

    @Override
    ProjectInternal getRootProject();

    ProjectInternal getRootProject(ProjectInternal referrer);

    BuildProject evaluate();

    ProjectInternal bindAllModelRules();

    @Override
    TaskContainerInternal getTasks();

    ScriptSource getBuildScriptSource();

    ProjectEvaluationListener getProjectEvaluationBroadcaster();

    void addRuleBasedPluginListener(RuleBasedPluginListener listener);

    @Override
    ProjectInternal project(String path) throws UnknownProjectException;

    ProjectEvaluationListener stepEvaluationListener(ProjectEvaluationListener listener,
                                                     Action<ProjectEvaluationListener> step);

    ProjectInternal project(ProjectInternal referrer, String path) throws UnknownProjectException;

    ProjectInternal project(ProjectInternal referrer, String path, Action<? super BuildProject> configureAction);

    @Override
    @Nullable
    ProjectInternal findProject(String path);

    @Nullable
    ProjectInternal findProject(ProjectInternal referrer, String path);

    Set<? extends ProjectInternal> getSubprojects(ProjectInternal referrer);

    void subprojects(ProjectInternal referrer, Action<? super BuildProject> configureAction);

    Set<? extends ProjectInternal> getAllprojects(ProjectInternal referrer);

    void allprojects(ProjectInternal referrer, Action<? super BuildProject> configureAction);


    /**
     * Returns the {@link ProjectState} that manages the state of this instance.
     */
    ProjectStateUnk getOwner();

    ServiceRegistry getServices();

    ProjectStateInternal getState();

    @Override
    ModelRegistry getModelRegistry();

    GradleInternal getGradle();

    void prepareForRuleBasedPlugins();

    FileResolver getFileResolver();

    ClassLoaderScope getClassLoaderScope();

    ClassLoaderScope getBaseClassLoaderScope();

    void setScript(groovy.lang.Script script);

    @Override
    PluginManagerInternal getPluginManager();

    void fireDeferredConfiguration();

    void addDeferredConfiguration(Runnable configuration);

    @Override
    ExtensionContainerInternal getExtensions();

    DynamicObject getInheritedScope();

    /**
     * Returns the property that stored {@link BuildProject#getStatus()}.
     * <p>
     * By exposing this property, the {@code base} plugin can override the default value without overriding the build configuration.
     * <p>
     * See: https://github.com/gradle/gradle/issues/16946
     */
    Property<Object> getInternalStatus();

    DependencyMetaDataProvider getDependencyMetaDataProvider();

    interface DetachedResolver {
//        RepositoryHandler getRepositories();

        DependencyHandler getDependencies();

//        ConfigurationContainer getConfigurations();
    }
}
