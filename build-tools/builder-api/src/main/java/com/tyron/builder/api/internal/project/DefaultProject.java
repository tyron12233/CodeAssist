package com.tyron.builder.api.internal.project;

import com.google.common.collect.Maps;
import com.google.common.primitives.Ints;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.PathValidation;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.UnknownProjectException;
import com.tyron.builder.api.configuration.project.ProjectEvaluator;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.DeleteSpec;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.scopes.ServiceRegistryFactory;
import com.tyron.builder.api.internal.tasks.TaskContainerInternal;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.providers.Property;
import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.util.Path;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

public class DefaultProject implements ProjectInternal {

    private final ProjectStateUnk owner;
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
    private String description;
    private Object group;
    private Object version;
    private List<String> defaultTasks = new ArrayList<>();
    private Property<Object> status;
    private File buildDir;

    public DefaultProject(String name,
                          @Nullable ProjectInternal parent,
                          File projectDir,
                          File buildFile,
                          GradleInternal gradle,
                          ProjectStateUnk owner,
                          ServiceRegistryFactory serviceRegistryFactory) {
        this.owner = owner;
        this.gradle = gradle;
//        this.classLoaderScope = selfClassLoaderScope;
//        this.baseClassLoaderScope = baseClassLoaderScope;
        this.rootProject = parent != null ? parent.getRootProject() : this;
        this.projectDir = projectDir;
        this.buildFile = buildFile;
        this.parent = parent;
        this.name = name;
        this.state = new ProjectStateInternal();
//        this.buildScriptSource = buildScriptSource;
//        this.gradle = gradle;

        if (parent == null) {
            depth = 0;
        } else {
            depth = parent.getDepth() + 1;
        }

        services = serviceRegistryFactory.createFor(this);
        taskContainer = services.get(TaskContainerInternal.class);
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
        return rootProject;
//        return getCrossProjectModelAccess().access(referrer, rootProject);
    }

    @Override
    public BuildProject evaluate() {
        getProjectEvaluator().evaluate(this, state);
        return this;
    }

    public ProjectEvaluator getProjectEvaluator() {
        return getServices().get(ProjectEvaluator.class);
    }

    @Override
    public ProjectInternal bindAllModelRules() {
        return null;
    }

    @Override
    public TaskContainerInternal getTasks() {
        return taskContainer;
    }

    @Override
    public void subprojects(Action<? super BuildProject> action) {

    }

    @Override
    public void allprojects(Action<? super BuildProject> action) {

    }

    @Override
    public void beforeEvaluate(Action<? super BuildProject> action) {

    }

    @Override
    public void afterEvaluate(Action<? super BuildProject> action) {

    }

    @Override
    public boolean hasProperty(String propertyName) {
        return false;
    }

    @Override
    public Map<String, ?> getProperties() {
        return null;
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
        return null;
    }

    @Override
    public <T> Iterable<T> configure(Iterable<T> objects, Action<? super T> configureAction) {
        return null;
    }

    @Override
    public ProjectInternal project(String path) throws UnknownProjectException {
        return null;
    }

    @Override
    public BuildProject project(String path, Action<? super BuildProject> configureAction) {
        return null;
    }

    @Override
    public Map<BuildProject, Set<Task>> getAllTasks(boolean recursive) {
        return null;
    }

    @Override
    public Set<Task> getTasksByName(String name, boolean recursive) {
        return null;
    }

    @Override
    public File getProjectDir() {
        return owner.getProjectDir();
    }

    @Override
    public File file(Object path) {
        return null;
    }

    @Override
    public File file(Object path, PathValidation validation) throws InvalidUserDataException {
        return null;
    }

    @Override
    public URI uri(Object path) {
        return null;
    }

    @Override
    public String relativePath(Object path) {
        return null;
    }

    @Override
    public ConfigurableFileCollection files(Object... paths) {
        return null;
    }

    @Override
    public ConfigurableFileCollection files(Object paths,
                                            Action<? super ConfigurableFileCollection> configureAction) {
        return null;
    }

    @Override
    public ConfigurableFileTree fileTree(Object baseDir) {
        return null;
    }

    @Override
    public ConfigurableFileTree fileTree(Object baseDir,
                                         Action<? super ConfigurableFileTree> configureAction) {
        return null;
    }

    @Override
    public ConfigurableFileTree fileTree(Map<String, ?> args) {
        return null;
    }

    @Override
    public FileTree zipTree(Object zipPath) {
        return null;
    }

    @Override
    public FileTree tarTree(Object tarPath) {
        return null;
    }

    @Override
    public <T> Provider<T> provider(Callable<T> value) {
        return null;
    }

    @Override
    public File mkdir(Object path) {
        return null;
    }

    @Override
    public boolean delete(Object... paths) {
        return false;
    }

    @Override
    public WorkResult delete(Action<? super DeleteSpec> action) {
        return null;
    }

    @Override
    public ProjectInternal project(ProjectInternal referrer,
                                   String path) throws UnknownProjectException {
        return null;
    }

    @Override
    public ProjectInternal project(ProjectInternal referrer,
                                   String path,
                                   Action<? super BuildProject> configureAction) {
        return null;
    }

    @Nullable
    @Override
    public ProjectInternal findProject(String path) {
        return null;
    }

    @Nullable
    @Override
    public ProjectInternal findProject(ProjectInternal referrer, String path) {
        return null;
    }

    @Override
    public Set<? extends ProjectInternal> getSubprojects(ProjectInternal referrer) {
        return null;
    }

    @Override
    public void subprojects(ProjectInternal referrer,
                            Action<? super BuildProject> configureAction) {

    }

    @Override
    public Set<? extends ProjectInternal> getAllprojects(ProjectInternal referrer) {
        return null;
    }

    @Override
    public void allprojects(ProjectInternal referrer,
                            Action<? super BuildProject> configureAction) {

    }

    @Override
    public ProjectStateUnk getOwner() {
        return owner;
    }

    @Override
    public ServiceRegistry getServices() {
        return services;
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

    }

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
        return parent;
//        return getCrossProjectModelAccess().access(referrer, parent);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return null;
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
        return null;
    }

    @Override
    public Map<String, BuildProject> getChildProjects() {
        Map<String, BuildProject> childProjects = Maps.newTreeMap();
        for (ProjectStateUnk project : owner.getChildProjects()) {
            childProjects.put(project.getName(), project.getMutableModel());
        }
        return childProjects;
    }

    @Override
    public void setProperty(String name, @Nullable Object value) {

    }

    @Override
    public BuildProject getProject() {
        return null;
    }

    @Override
    public Set<BuildProject> getAllprojects() {
        return null;
    }

    @Override
    public Set<BuildProject> getSubprojects() {
        return null;
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

    }

    @Override
    public BuildProject evaluationDependsOn(String path) throws UnknownProjectException {
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

    @Override
    public int getDepth() {
        return depth;
    }


    @Override
    public int depthCompare(BuildProject otherProject) {
        return Ints.compare(getDepth(), otherProject.getDepth());
    }

    @Override
    public int compareTo(BuildProject otherProject) {
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
    public GradleInternal getGradle() {
        return gradle;
    }
}