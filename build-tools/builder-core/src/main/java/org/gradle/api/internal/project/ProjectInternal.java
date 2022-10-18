package org.gradle.api.internal.project;

import org.gradle.api.Action;
import org.gradle.api.ProjectEvaluationListener;
import org.gradle.api.UnknownProjectException;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.internal.DomainObjectContext;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.api.internal.file.HasScriptServices;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.plugins.ExtensionContainerInternal;
import org.gradle.api.internal.plugins.PluginAwareInternal;
import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.api.provider.Property;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.metaobject.DynamicObject;
import org.gradle.internal.model.RuleBasedPluginListener;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.api.internal.tasks.TaskContainerInternal;
import org.gradle.api.Project;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.model.internal.registry.ModelRegistryScope;

import org.jetbrains.annotations.Nullable;

import java.util.Set;

public interface ProjectInternal extends Project, ProjectIdentifier, HasScriptServices, DomainObjectContext, ModelRegistryScope, PluginAwareInternal {

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

    Project evaluate();

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

    ProjectInternal project(ProjectInternal referrer, String path, Action<? super Project> configureAction);

    @Override
    @Nullable
    ProjectInternal findProject(String path);

    @Nullable
    ProjectInternal findProject(ProjectInternal referrer, String path);

    Set<? extends ProjectInternal> getSubprojects(ProjectInternal referrer);

    void subprojects(ProjectInternal referrer, Action<? super Project> configureAction);

    Set<? extends ProjectInternal> getAllprojects(ProjectInternal referrer);

    void allprojects(ProjectInternal referrer, Action<? super Project> configureAction);


    /**
     * Returns the {@link org.gradle.api.ProjectState} that manages the state of this instance.
     */
    ProjectState getOwner();

    ServiceRegistry getServices();

    @Override
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
     * Returns the property that stored {@link Project#getStatus()}.
     * <p>
     * By exposing this property, the {@code base} plugin can override the default value without overriding the build configuration.
     * <p>
     * See: https://github.com/gradle/gradle/issues/16946
     */
    Property<Object> getInternalStatus();

    DependencyMetaDataProvider getDependencyMetaDataProvider();

    /**
     * Returns a dependency resolver which can be used to resolve
     * dependencies in isolation from the project itself. This is
     * particularly useful if the repositories or configurations
     * needed for resolution shouldn't leak to the project state.
     *
     * @return a detached resolver
     */
    DetachedResolver newDetachedResolver();

    interface DetachedResolver {
        RepositoryHandler getRepositories();

        DependencyHandler getDependencies();

        ConfigurationContainer getConfigurations();
    }
}
