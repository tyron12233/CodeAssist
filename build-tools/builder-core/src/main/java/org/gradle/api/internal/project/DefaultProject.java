package org.gradle.api.internal.project;

import static org.gradle.util.internal.GUtil.addMaps;

import static java.util.Collections.singletonMap;

import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.NamedDomainObjectFactory;
import org.gradle.api.PathValidation;
import org.gradle.api.ProjectEvaluationListener;
import org.gradle.api.Task;
import org.gradle.api.UnknownProjectException;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.ArtifactHandler;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.component.SoftwareComponentContainer;
import org.gradle.api.file.CopySpec;
import org.gradle.api.internal.DomainObjectContext;
import org.gradle.api.internal.DynamicObjectAware;
import org.gradle.api.internal.MutationGuards;
import org.gradle.api.internal.ProcessOperations;
import org.gradle.api.internal.artifacts.DependencyManagementServices;
import org.gradle.api.internal.artifacts.DependencyResolutionServices;
import org.gradle.api.internal.artifacts.dsl.dependencies.UnknownProjectFinder;
import org.gradle.api.internal.collections.DomainObjectCollectionFactory;
import org.gradle.api.internal.file.DefaultProjectLayout;
import org.gradle.api.internal.file.FileCollectionFactory;
import org.gradle.api.internal.initialization.ClassLoaderScope;
import org.gradle.api.internal.initialization.ScriptHandlerFactory;
import org.gradle.api.internal.initialization.ScriptHandlerInternal;
import org.gradle.api.internal.plugins.DefaultObjectConfigurationAction;
import org.gradle.api.internal.plugins.PluginManagerInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.provider.ProviderFactory;
import org.gradle.api.resources.ResourceHandler;
import org.gradle.configuration.ConfigurationTargetIdentifier;
import org.gradle.configuration.ScriptPluginFactory;
import org.gradle.configuration.internal.ListenerBuildOperationDecorator;
import org.gradle.configuration.project.ProjectEvaluator;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.FileTree;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.configurations.DependencyMetaDataProvider;
import org.gradle.groovy.scripts.ScriptSource;
import org.gradle.internal.Actions;
import org.gradle.internal.Cast;
import org.gradle.internal.Factory;
import org.gradle.internal.deprecation.DeprecationLogger;
import org.gradle.internal.event.ListenerBroadcast;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DeleteSpec;
import org.gradle.api.internal.file.FileLookup;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.file.FileResolver;
import org.gradle.internal.extensibility.ExtensibleDynamicObject;
import org.gradle.api.internal.plugins.ExtensionContainerInternal;
import org.gradle.internal.instantiation.InstantiatorFactory;
import org.gradle.internal.logging.LoggingManagerInternal;
import org.gradle.internal.logging.StandardOutputCapture;
import org.gradle.internal.metaobject.BeanDynamicObject;
import org.gradle.internal.metaobject.DynamicObject;
import org.gradle.internal.model.ModelContainer;
import org.gradle.internal.model.RuleBasedPluginListener;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.resource.TextUriResourceLoader;
import org.gradle.internal.service.scopes.ServiceRegistryFactory;
import org.gradle.api.internal.tasks.TaskContainerInternal;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.Convention;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.WorkResult;
import org.gradle.listener.ClosureBackedMethodInvocationDispatch;
import org.gradle.model.internal.registry.ModelRegistry;
import org.gradle.util.Configurable;
import org.gradle.util.ConfigureUtil;
import org.gradle.util.Path;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;

import javax.inject.Inject;

import groovy.lang.Closure;
import groovy.lang.Script;

public abstract class DefaultProject extends AbstractPluginAware implements ProjectInternal, DynamicObjectAware {

    private static final Logger BUILD_LOGGER = Logging.getLogger(Project.class);

    private final ProjectState owner;
    private final ProjectInternal rootProject;
    private final File projectDir;
    private final File buildFile;
    private final ProjectInternal parent;
    private final String name;
    private final ProjectStateInternal state;
    private final int depth;
    private final ServiceRegistry services;
    private final TaskContainerInternal taskContainer;
    private final GradleInternal gradle;
    private final ScriptSource buildScriptSource;
    private final ClassLoaderScope classLoaderScope;
    private final ClassLoaderScope baseClassLoaderScope;
    private final ExtensibleDynamicObject extensibleDynamicObject;
    private String description;
    private Object group;
    private Object version;
    private List<String> defaultTasks = new ArrayList<>();
    private Property<Object> status;
    private File buildDir;
    private ListenerBroadcast<ProjectEvaluationListener> evaluationListener = newProjectEvaluationListenerBroadcast();
    private ConfigurationContainer configurationContainer;
    private DependencyHandler dependencyHandler;
    private ArtifactHandler artifactHandler;
    private final ListenerBroadcast<RuleBasedPluginListener> ruleBasedPluginListenerBroadcast = new ListenerBroadcast<>(RuleBasedPluginListener.class);
    private boolean preparedForRuleBasedPlugins;
    private FileResolver fileResolver;

    public DefaultProject(String name,
                          @Nullable ProjectInternal parent,
                          File projectDir,
                          File buildFile,
                          ScriptSource buildScriptSource,
                          GradleInternal gradle,
                          ProjectState owner,
                          ServiceRegistryFactory serviceRegistryFactory,
                          ClassLoaderScope selfClassLoaderScope,
                          ClassLoaderScope baseClassLoaderScope
    ) {
        this.owner = owner;
        this.classLoaderScope = selfClassLoaderScope;
        this.baseClassLoaderScope = baseClassLoaderScope;
        this.rootProject = parent != null ? parent.getRootProject() : this;
        this.projectDir = projectDir;
        this.buildFile = buildFile;
        this.parent = parent;
        this.name = name;
        this.state = new ProjectStateInternal();
        this.buildScriptSource = buildScriptSource;
        this.gradle = gradle;

        services = serviceRegistryFactory.createFor(this);
        taskContainer = services.get(TaskContainerInternal.class);

        extensibleDynamicObject = new ExtensibleDynamicObject(this, Project.class, services.get(
                InstantiatorFactory.class).decorateLenient(services));
        if (parent != null) {
            extensibleDynamicObject.setParent(parent.getInheritedScope());
        }
        extensibleDynamicObject.addObject(taskContainer.getTasksAsDynamicObject(), ExtensibleDynamicObject.Location.AfterConvention);

        if (parent == null) {
            depth = 0;
        } else {
            depth = parent.getDepth() + 1;
        }

        setBuildDir(new File(projectDir, "build"));
    }

    private ListenerBroadcast<ProjectEvaluationListener> newProjectEvaluationListenerBroadcast() {
        return new ListenerBroadcast<>(ProjectEvaluationListener.class);
    }

    private String instanceDescriptorFor(String path) {
        return "Project.<init>." + path + "()";
    }

    @Override
    public ProjectInternal getRootProject() {
        return getRootProject(this);
    }

    @Override
    public ProjectInternal getRootProject(ProjectInternal referrer) {
        return getCrossProjectModelAccess().access(referrer, rootProject);
    }

    @Override
    public ArtifactHandler getArtifacts() {
        if (artifactHandler == null) {
            artifactHandler = services.get(ArtifactHandler.class);
        }
        return artifactHandler;
    }

    public void setArtifactHandler(ArtifactHandler artifactHandler) {
        this.artifactHandler = artifactHandler;
    }

    @Inject
    @Override
    public abstract RepositoryHandler getRepositories();

    @Override
    public ConfigurationContainer getConfigurations() {
        if (configurationContainer == null) {
            configurationContainer = services.get(ConfigurationContainer.class);
        }
        return configurationContainer;
    }

    @Override
    public Convention getConvention() {
        return extensibleDynamicObject.getConvention();
    }

    @Override
    public Project evaluate() {
        getProjectEvaluator().evaluate(this, state);
        return this;
    }

    @Inject
    protected abstract ProjectEvaluator getProjectEvaluator();

    @Override
    public ProjectInternal bindAllModelRules() {
        return null;
    }

    @Override
    public TaskContainerInternal getTasks() {
        return taskContainer;
    }

    @Override
    public ScriptSource getBuildScriptSource() {
        return buildScriptSource;
    }

    @Override
    public ProjectEvaluationListener getProjectEvaluationBroadcaster() {
        return evaluationListener.getSource();
    }

    @Override
    public void beforeEvaluate(Action<? super Project> action) {
        assertMutatingMethodAllowed("beforeEvaluate(Action)");
        evaluationListener.add("beforeEvaluate", getListenerBuildOperationDecorator().decorate("Project.beforeEvaluate", action));
    }

    @Override
    public void beforeEvaluate(Closure closure) {
        assertMutatingMethodAllowed("beforeEvaluate(Closure)");
        evaluationListener.add(new ClosureBackedMethodInvocationDispatch("beforeEvaluate", getListenerBuildOperationDecorator().decorate("Project.beforeEvaluate", Cast.<Closure<?>>uncheckedNonnullCast(closure))));
    }

    @Override
    public void afterEvaluate(Closure closure) {
        assertMutatingMethodAllowed("afterEvaluate(Closure)");
        failAfterProjectIsEvaluated("afterEvaluate(Closure)");
        evaluationListener.add(new ClosureBackedMethodInvocationDispatch("afterEvaluate", getListenerBuildOperationDecorator().decorate("Project.afterEvaluate", Cast.<Closure<?>>uncheckedNonnullCast(closure))));
    }

    @Override
    public void afterEvaluate(Action<? super Project> action) {
        assertMutatingMethodAllowed("afterEvaluate(Action)");
        failAfterProjectIsEvaluated("afterEvaluate(Action)");
        evaluationListener.add("afterEvaluate", getListenerBuildOperationDecorator().decorate("Project.afterEvaluate", action));
    }


    @Override
    public boolean hasProperty(String propertyName) {
        return extensibleDynamicObject.hasProperty(propertyName);
    }

    @Override
    public Map<String, ?> getProperties() {
        return Objects.requireNonNull(DeprecationLogger.whileDisabled(
                (Factory<Map<String, ?>>) extensibleDynamicObject::getProperties));
    }

    @Nullable
    @Override
    public Object property(String propertyName) {
        return null;
    }

    @Nullable
    @Override
    public Object findProperty(String propertyName) {
        return null;
    }

    @Override
    public Logger getLogger() {
        return BUILD_LOGGER;
    }

    private LoggingManagerInternal loggingManagerInternal;

    @Override
    public LoggingManagerInternal getLogging() {
        if (loggingManagerInternal == null) {
            loggingManagerInternal = services.get(LoggingManagerInternal.class);
        }
        return loggingManagerInternal;
    }


    @Inject
    @Override
    public abstract SoftwareComponentContainer getComponents();

    @Override
    public StandardOutputCapture getStandardOutputCapture() {
        return getLogging();
    }

    @Override
    public <T> Iterable<T> configure(Iterable<T> objects, Action<? super T> configureAction) {
        for (T object : objects) {
            configureAction.execute(object);
        }
        return objects;
    }

    @Override
    public ProjectInternal project(String path) throws UnknownProjectException {
        return project(this, path);
    }

    @Override
    public Project project(String path, Action<? super Project> configureAction) {
        ProjectInternal project = project(path);
        configureAction.execute(project);
        return project;
    }

    @Override
    public ProjectEvaluationListener stepEvaluationListener(ProjectEvaluationListener listener, Action<ProjectEvaluationListener> step) {
        ListenerBroadcast<ProjectEvaluationListener> original = this.evaluationListener;
        ListenerBroadcast<ProjectEvaluationListener> nextBatch = newProjectEvaluationListenerBroadcast();
        this.evaluationListener = nextBatch;
        try {
            step.execute(listener);
        } finally {
            this.evaluationListener = original;
        }
        return nextBatch.isEmpty()
                ? null
                : nextBatch.getSource();
    }

    private void assertMutatingMethodAllowed(String methodName) {
        MutationGuards.of(getProjectConfigurator()).assertMutationAllowed(methodName, this, Project.class);
    }

    private void failAfterProjectIsEvaluated(String methodPrototype) {
        if (!state.isUnconfigured() && !state.isConfiguring()) {
            throw new InvalidUserCodeException("Cannot run Project." + methodPrototype + " when the project is already evaluated.");
        }
    }

    @Override
    public Map<Project, Set<Task>> getAllTasks(boolean recursive) {
        final Map<Project, Set<Task>> foundTargets = new TreeMap<>();
        Action<Project> action = project -> {
            // Don't force evaluation of rules here, let the task container do what it needs to
            ((ProjectInternal) project).getOwner().ensureTasksDiscovered();

            foundTargets.put(project, new TreeSet<>(project.getTasks()));
        };
        if (recursive) {
            allprojects(action);
        } else {
            action.execute(this);
        }
        return foundTargets;
    }

    @Override
    public Set<Task> getTasksByName(final String name, boolean recursive) {
        if (Strings.isNullOrEmpty(name)) {
            throw new InvalidUserDataException("Name is not specified!");
        }
        final Set<Task> foundTasks = new HashSet<>();
        Action<Project> action = project -> {
            // Don't force evaluation of rules here, let the task container do what it needs to
            ((ProjectInternal) project).getOwner().ensureTasksDiscovered();

            Task task = project.getTasks().findByName(name);
            if (task != null) {
                foundTasks.add(task);
            }
        };
        if (recursive) {
            allprojects(action);
        } else {
            action.execute(this);
        }
        return foundTasks;
    }

    @Override
    public File getProjectDir() {
        return projectDir;
    }

    @Override
    @Inject
    public abstract FileOperations getFileOperations();

    @Override
    @Inject
    public abstract ProviderFactory getProviders();

    @Override
    @Inject
    public abstract ObjectFactory getObjects();

    @Override
    @Inject
    public abstract DefaultProjectLayout getLayout();

    private FileResolver getProjectFileResolver() {
        FileLookup fileLookup = getServices().get(FileLookup.class);
        return fileLookup.getFileResolver(getProjectDir());
    }

    @Override
    public File file(Object path) {
        return file(path, PathValidation.NONE);
    }


    @Override
    public File file(Object path, PathValidation validation) throws InvalidUserDataException {
        return getProjectFileResolver().resolve(path, validation);
    }

    @Override
    public URI uri(Object path) {
        return getProjectFileResolver().resolveUri(path);
    }

    @Override
    public String relativePath(Object path) {
        return getProjectFileResolver().resolveAsRelativePath(path);
    }

    @Override
    public ConfigurableFileCollection files(Object... paths) {
        return getObjects().fileCollection().from(paths);
    }

    @Override
    public ConfigurableFileCollection files(Object paths,
                                            Action<? super ConfigurableFileCollection> configureAction) {
        ConfigurableFileCollection files = files(paths);
        configureAction.execute(files);
        return files;
    }

    @Override
    public ConfigurableFileTree fileTree(Object baseDir) {
        return getObjects().fileTree().from(baseDir);
    }

    @Override
    public ConfigurableFileTree fileTree(Object baseDir,
                                         Action<? super ConfigurableFileTree> configureAction) {
        ConfigurableFileTree files = fileTree(baseDir);
        configureAction.execute(files);
        return files;
    }

    @Override
    public ConfigurableFileTree fileTree(Map<String, ?> args) {
        return getFileOperations().fileTree(args);
    }

    @Override
    public FileTree zipTree(Object zipPath) {
        return getFileOperations().zipTree(zipPath);
    }

    @Override
    public FileTree tarTree(Object tarPath) {
        return getFileOperations().tarTree(tarPath);
    }

    @Override
    public ResourceHandler getResources() {
        return getFileOperations().getResources();
    }

    @Override
    public <T> Provider<T> provider(Callable<T> value) {
        return getProviders().provider(value);
    }

    @Override
    public void buildscript(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getBuildscript());
    }

    @Override
    public Task task(String task) {
        return taskContainer.create(task);
    }

    public Task task(Object task) {
        return taskContainer.create(task.toString());
    }

    @Override
    public Task task(String task, Action<? super Task> configureAction) {
        return taskContainer.create(task, configureAction);
    }

    @Override
    public Task task(String task, Closure configureClosure) {
        return taskContainer.create(task).configure(configureClosure);
    }

    public Task task(Object task, Closure configureClosure) {
        return task(task.toString(), configureClosure);
    }

    @Override
    public Task task(Map options, String task) {
        return taskContainer.create(addMaps(Cast.uncheckedNonnullCast(options), singletonMap(Task.TASK_NAME, task)));
    }

    public Task task(Map options, Object task) {
        return task(options, task.toString());
    }

    @Override
    public Task task(Map options, String task, Closure configureClosure) {
        return taskContainer.create(addMaps(Cast.uncheckedNonnullCast(options), singletonMap(Task.TASK_NAME, task))).configure(configureClosure);
    }

    public Task task(Map options, Object task, Closure configureClosure) {
        return task(options, task.toString(), configureClosure);
    }

    @Override
    public CopySpec copySpec(Closure closure) {
        return ConfigureUtil.configure(closure, copySpec());
    }

    @Override
    public CopySpec copySpec(Action<? super CopySpec> action) {
        return Actions.with(copySpec(), action);
    }

    @Override
    public CopySpec copySpec() {
        return getFileOperations().copySpec();
    }


    @Override
    public File mkdir(Object path) {
        return getFileOperations().mkdir(path);
    }

    @Override
    public boolean delete(Object... paths) {
        return getFileOperations().delete(paths);
    }

    @Override
    public WorkResult delete(Action<? super DeleteSpec> action) {
        throw new UnsupportedOperationException("This method is not yet supported.");
    }

    @Override
    public ProjectInternal project(ProjectInternal referrer,
                                   String path) throws UnknownProjectException {
        ProjectInternal project = getCrossProjectModelAccess().findProject(referrer, this, path);
        if (project == null) {
            throw new UnknownProjectException(String.format("Project with path '%s' could not be found in %s.", path, this));
        }
        return project;
    }

    @Override
    public ProjectInternal project(ProjectInternal referrer, String path, Action<? super Project> configureAction) {
        ProjectInternal project = project(referrer, path);
        getProjectConfigurator().project(project, configureAction);
        return project;
    }

    @Nullable
    @Override
    public ProjectInternal findProject(String path) {
        return findProject(this, path);
    }

    @Nullable
    @Override
    public ProjectInternal findProject(ProjectInternal referrer, String path) {
        return getCrossProjectModelAccess().findProject(referrer, this, path);
    }


    @Override
    public void subprojects(Action<? super Project> action) {
        subprojects(this, action);
    }

    @Override
    public void subprojects(ProjectInternal referrer, Action<? super Project> configureAction) {
        getProjectConfigurator().subprojects(getCrossProjectModelAccess().getSubprojects(referrer, this), configureAction);
    }

    @Override
    public Set<? extends ProjectInternal> getSubprojects(ProjectInternal referrer) {
        return getCrossProjectModelAccess().getSubprojects(referrer, this);
    }

    @Override
    public Set<Project> getSubprojects() {
        return Cast.uncheckedCast(getSubprojects(this));
    }

    @Override
    public Set<? extends ProjectInternal> getAllprojects(ProjectInternal referrer) {
        return getCrossProjectModelAccess().getAllprojects(referrer, this);
    }

    @Override
    public void allprojects(Action<? super Project> action) {
        allprojects(this, action);
    }

    @Override
    public void allprojects(ProjectInternal referrer,
                            Action<? super Project> configureAction) {
        getProjectConfigurator().allprojects(getCrossProjectModelAccess().getAllprojects(referrer, this), configureAction);
    }

    @Override
    public ProjectState getOwner() {
        return owner;
    }


    @Override
    public DetachedResolver newDetachedResolver() {
        DependencyManagementServices dms = getServices().get(DependencyManagementServices.class);
        InstantiatorFactory instantiatorFactory = services.get(InstantiatorFactory.class);
        DefaultServiceRegistry lookup = new DefaultServiceRegistry(services);
        lookup.addProvider(new Object() {
            public DependencyResolutionServices createServices() {
                return dms.create(
                        services.get(FileResolver.class),
                        services.get(FileCollectionFactory.class),
                        services.get(DependencyMetaDataProvider.class),
                        new UnknownProjectFinder("Detached resolvers do not support resolving projects"),
                        new DetachedDependencyResolutionDomainObjectContext(services.get(
                                DomainObjectContext.class))
                );
            }
        });
        return instantiatorFactory.decorate(lookup).newInstance(
                LocalDetachedResolver.class
        );
    }

    public static class LocalDetachedResolver implements DetachedResolver {
        private final DependencyResolutionServices resolutionServices;

        @Inject
        public LocalDetachedResolver(DependencyResolutionServices resolutionServices) {
            this.resolutionServices = resolutionServices;
        }

        @Override
        public RepositoryHandler getRepositories() {
            return resolutionServices.getResolveRepositoryHandler();
        }

        @Override
        public DependencyHandler getDependencies() {
            return resolutionServices.getDependencyHandler();
        }

        @Override
        public ConfigurationContainer getConfigurations() {
            return resolutionServices.getConfigurationContainer();
        }
    }

    @Override
    public ServiceRegistry getServices() {
        return services;
    }

    @Override
    public ProcessOperations getProcessOperations() {
        return services.get(ProcessOperations.class);
    }

    @Override
    public File getBuildFile() {
        return buildFile;
    }

    @Override
    public File getRootDir() {
        return rootProject.getProjectDir();
    }

    @Override
    public File getBuildDir() {
        return buildDir;
    }

    @Override
    public void setBuildDir(File path) {
        this.buildDir = path;
    }

    @Override
    public void setBuildDir(Object path) {
        setBuildDir(file(path));
    }

    @Override
    public DependencyHandler getDependencies() {
        if (dependencyHandler == null) {
            dependencyHandler = services.get(DependencyHandler.class);
        }
        return dependencyHandler;
    }


    @Override
    public void dependencies(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getDependencies());
        System.out.println("");
    }

    @Override
    public void artifacts(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getArtifacts());
    }

    @Override
    public void artifacts(Action<? super ArtifactHandler> configureAction) {
        configureAction.execute(getArtifacts());
    }

    @Override
    public void configurations(Closure configureClosure) {
        ((Configurable<?>) getConfigurations()).configure(configureClosure);
    }

    @Override
    public void repositories(Closure configureClosure) {
        ConfigureUtil.configure(configureClosure, getRepositories());
    }

    @Inject
    @Override
    public abstract ScriptHandlerInternal getBuildscript();

    @Override
    public ProjectInternal getParent() {
        return getParent(this);
    }


    @Nullable
    @Override
    public ProjectInternal getParent(ProjectInternal referrer) {
        if (parent == null) {
            return null;
        }
        return getCrossProjectModelAccess().access(referrer, parent);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        StringBuilder builder = new StringBuilder();
        if (parent == null && gradle.isRootBuild()) {
            builder.append("root project '");
            builder.append(name);
            builder.append('\'');
        } else {
            builder.append("project '");
            builder.append(getIdentityPath());
            builder.append("'");
        }
        return builder.toString();
    }

    @Override
    public DynamicObject getAsDynamicObject() {
        return extensibleDynamicObject;
    }

    @Override
    public DynamicObject getInheritedScope() {
        return extensibleDynamicObject.getInheritable();
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public Object getGroup() {
        if (group != null) {
            return group;
        } else if (this == rootProject) {
            return "";
        }
        group = rootProject.getName() +
                (getParent() == rootProject ? "" : "." + getParent().getPath().substring(1).replace(':', '.'));
        return group;
    }

    @Override
    public void setGroup(Object group) {
        this.group = group;
    }

    @Override
    public Object getVersion() {
        return version == null ? DEFAULT_VERSION : version;
    }

    @Override
    public void setVersion(Object version) {
        this.version = version;
    }

    @Override
    public Object getStatus() {
        return getInternalStatus().get();
    }

    @Override
    public void setStatus(Object s) {
        getInternalStatus().set(s);
    }

    public Property<Object> getInternalStatus() {
        if (status == null) {
            status = getObjects().property(Object.class).convention(DEFAULT_STATUS);
        }
        return status;
    }

    @Override
    public Map<String, Project> getChildProjects() {
        Map<String, Project> childProjects = Maps.newTreeMap();
        for (ProjectState project : owner.getChildProjects()) {
            childProjects.put(project.getName(), project.getMutableModel());
        }
        return childProjects;
    }

    @Override
    public void setProperty(String name, @Nullable Object value) {

    }

    @Override
    public ProjectInternal getProject() {
        return this;
    }

    @Override
    public Set<Project> getAllprojects() {
        return Cast.uncheckedCast(getAllprojects(this));
    }

    @Override
    public List<String> getDefaultTasks() {
        return defaultTasks;
    }

    @Override
    public void setDefaultTasks(List<String> defaultTasks) {
        this.defaultTasks = defaultTasks;
    }

    @Override
    public void defaultTasks(String... defaultTasks) {
        if (this.defaultTasks == null) {
            this.defaultTasks = new ArrayList<>();
        }
        this.defaultTasks.addAll(Arrays.asList(defaultTasks));
    }

    @Override
    public Project evaluationDependsOn(String path) throws UnknownProjectException {
        return null;
    }

    @Override
    public void evaluationDependsOnChildren() {

    }

    @Override
    public ProjectStateInternal getState() {
        return state;
    }

    @Override
    public String getPath() {
        return owner.getProjectPath().toString();
    }

    @javax.annotation.Nullable
    @Override
    public ProjectIdentifier getParentIdentifier() {
        return parent;
    }

    @Override
    public int getDepth() {
        return depth;
    }

    @NotNull
    @Override
    public String toString() {
        return getDisplayName();
    }


    @Override
    public int depthCompare(Project otherProject) {
        return Ints.compare(getDepth(), otherProject.getDepth());
    }

    @Override
    public int compareTo(Project otherProject) {
        int depthCompare = depthCompare(otherProject);
        if (depthCompare == 0) {
            return getProjectPath().compareTo(((ProjectInternal) otherProject).getProjectPath());
        } else {
            return depthCompare;
        }
    }

    @Override
    public String absoluteProjectPath(String path) {
        return getProjectPath().absolutePath(path);
    }

    @Override
    public String relativeProjectPath(String path) {
        return getProjectPath().relativePath(path);
    }

    @Override
    public Path getProjectPath() {
        return owner.getProjectPath();
    }

    @Override
    public ModelContainer<?> getModel() {
        return owner;
    }

    @Override
    public Path getBuildPath() {
        return gradle.getIdentityPath();
    }

    @Override
    public Path projectPath(String name) {
        return getProjectPath().child(name);
    }

    @Override
    public boolean isScript() {
        return false;
    }

    @Override
    public boolean isRootScript() {
        return false;
    }

    @Override
    public boolean isPluginContext() {
        return false;
    }

    @Override
    public boolean isDetachedState() {
        return ProjectInternal.super.isDetachedState();
    }

    @Override
    public Path getIdentityPath() {
        return owner.getIdentityPath();
    }

    @Override
    public Path identityPath(String name) {
        return getIdentityPath().child(name);
    }

    @Override
    @Inject
    public abstract DependencyMetaDataProvider getDependencyMetaDataProvider();

    @Override
    public GradleInternal getGradle() {
        return gradle;
    }

    @Override
    public ClassLoaderScope getClassLoaderScope() {
        return classLoaderScope;
    }

    @Override
    public ClassLoaderScope getBaseClassLoaderScope() {
        return baseClassLoaderScope;
    }

    @Override
    public void setScript(Script buildScript) {
        extensibleDynamicObject.addObject(new BeanDynamicObject(buildScript).withNoProperties().withNotImplementsMissing(),
                ExtensibleDynamicObject.Location.BeforeConvention);
    }



    @Override
    public <T> NamedDomainObjectContainer<T> container(Class<T> type) {
        return getServices().get(DomainObjectCollectionFactory.class).newNamedDomainObjectContainerUndecorated(type);
    }

    @Override
    public <T> NamedDomainObjectContainer<T> container(Class<T> type, NamedDomainObjectFactory<T> factory) {
        return getServices().get(DomainObjectCollectionFactory.class).newNamedDomainObjectContainer(type, factory);
    }

    @Override
    public <T> NamedDomainObjectContainer<T> container(Class<T> type, Closure factoryClosure) {
        return getServices().get(DomainObjectCollectionFactory.class).newNamedDomainObjectContainer(type, factoryClosure);
    }

    @Override
    public ExtensionContainerInternal getExtensions() {
        return ((ExtensionContainerInternal) getConvention());
    }


    @Override
    public void addDeferredConfiguration(Runnable configuration) {
        getDeferredProjectConfiguration().add(configuration);
    }

    @Override
    public void addRuleBasedPluginListener(RuleBasedPluginListener listener) {
        if (preparedForRuleBasedPlugins) {
            listener.prepareForRuleBasedPlugins(this);
        } else {
            ruleBasedPluginListenerBroadcast.add(listener);
        }
    }

    @Override
    public void prepareForRuleBasedPlugins() {
        if (!preparedForRuleBasedPlugins) {
            preparedForRuleBasedPlugins = true;
            ruleBasedPluginListenerBroadcast.getSource().prepareForRuleBasedPlugins(this);
        }
    }

    @Inject
    @Override
    public abstract ModelRegistry getModelRegistry();

    @Inject
    @Override
    public abstract PluginManagerInternal getPluginManager();


    @Override
    protected DefaultObjectConfigurationAction createObjectConfigurationAction() {
        TextUriResourceLoader.Factory textUriResourceLoaderFactory = services.get(TextUriResourceLoader.Factory.class);
        return new DefaultObjectConfigurationAction(getFileResolver(), getScriptPluginFactory(), getScriptHandlerFactory(), getBaseClassLoaderScope(), textUriResourceLoaderFactory, this);
    }

    // getter from services

    @Override
    public ConfigurationTargetIdentifier getConfigurationTargetIdentifier() {
        return services.get(ConfigurationTargetIdentifier.class);
    }

    @Override
    public FileResolver getFileResolver() {
        if (fileResolver == null) {
            fileResolver = services.get(FileResolver.class);
        }
        return fileResolver;
    }

    protected ScriptPluginFactory getScriptPluginFactory() {
        return services.get(ScriptPluginFactory.class);
    }

    protected ScriptHandlerFactory getScriptHandlerFactory() {
        return services.get(ScriptHandlerFactory.class);
    }

    @Inject
    protected abstract DeferredProjectConfiguration getDeferredProjectConfiguration();

    @Override
    public void fireDeferredConfiguration() {
        getDeferredProjectConfiguration().fire();
    }

    @Inject
    protected abstract CrossProjectModelAccess getCrossProjectModelAccess();

    protected CrossProjectConfigurator getProjectConfigurator() {
        return services.get(CrossProjectConfigurator.class);
    }

    @Inject
    protected abstract ListenerBuildOperationDecorator getListenerBuildOperationDecorator();

    private static class DetachedDependencyResolutionDomainObjectContext implements DomainObjectContext {
        private final DomainObjectContext delegate;

        private DetachedDependencyResolutionDomainObjectContext(DomainObjectContext delegate) {
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
        @Nullable
        public Path getProjectPath() {
            return delegate.getProjectPath();
        }

        @Override
        @Nullable
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
        public boolean isRootScript() {
            return delegate.isRootScript();
        }

        @Override
        public boolean isPluginContext() {
            return delegate.isPluginContext();
        }

        @Override
        public boolean isScript() {
            return delegate.isScript();
        }

        @Override
        public boolean isDetachedState() {
            return true;
        }
    }
}