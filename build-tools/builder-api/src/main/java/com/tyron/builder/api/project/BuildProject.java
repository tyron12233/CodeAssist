package com.tyron.builder.api.project;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.PathValidation;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.UnknownProjectException;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.FileTree;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.DeleteSpec;
import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.tasks.TaskContainer;
import com.tyron.builder.api.tasks.WorkResult;
import com.tyron.builder.api.util.Path;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

public interface BuildProject extends Comparable<BuildProject> {

    /**
     * The default project build file name.
     */
    String DEFAULT_BUILD_FILE = "build.gradle";

    /**
     * The hierarchy separator for project and task path names.
     */
    String PATH_SEPARATOR = ":";

    /**
     * The default build directory name.
     */
    String DEFAULT_BUILD_DIR_NAME = "build";

    String GRADLE_PROPERTIES = "gradle.properties";

    String SYSTEM_PROP_PREFIX = "systemProp";

    String DEFAULT_VERSION = "unspecified";

    String DEFAULT_STATUS = "release";

    /**
     * <p>Returns the root project for the hierarchy that this project belongs to.  In the case of a single-project
     * build, this method returns this project.</p>
     *
     * @return The root project. Never returns null.
     */
    BuildProject getRootProject();

    /**
     * <p>Returns the root directory of this project. The root directory is the project directory of the root
     * project.</p>
     *
     * @return The root directory. Never returns null.
     */
    File getRootDir();

    /**
     * <p>Returns the build directory of this project.  The build directory is the directory which all artifacts are
     * generated into.  The default value for the build directory is <code><i>projectDir</i>/build</code></p>
     *
     * @return The build directory. Never returns null.
     */
    File getBuildDir();

    /**
     * <p>Sets the build directory of this project. The build directory is the directory which all artifacts are
     * generated into.</p>
     *
     * @param path The build directory
     * @since 4.0
     */
    void setBuildDir(File path);

    /**
     * <p>Sets the build directory of this project. The build directory is the directory which all artifacts are
     * generated into. The path parameter is evaluated as described for {@link #file(Object)}. This mean you can use,
     * amongst other things, a relative or absolute path or File object to specify the build directory.</p>
     *
     * @param path The build directory. This is evaluated as per {@link #file(Object)}
     */
    void setBuildDir(Object path);

    /**
     * The build script for this project.
     * <p>
     * If the file exists, it will be evaluated against this project when this project is configured.
     *
     * @return the build script for this project.
     */
    File getBuildFile();

    /**
     * <p>Returns the parent project of this project, if any.</p>
     *
     * @return The parent project, or null if this is the root project.
     */
    @Nullable
    BuildProject getParent();

    /**
     * <p>Returns the name of this project. The project's name is not necessarily unique within a project hierarchy. You
     * should use the {@link #getPath()} method for a unique identifier for the project.</p>
     *
     * @return The name of this project. Never return null.
     */
    String getName();

    /**
     * Returns a human-consumable display name for this project.
     */
    String getDisplayName();

    /**
     * Returns the description of this project, if any.
     *
     * @return the description. May return null.
     */
    @Nullable
    String getDescription();

    /**
     * Sets a description for this project.
     *
     * @param description The description of the project. Might be null.
     */
    void setDescription(@Nullable String description);

    /**
     * <p>Returns the group of this project. Gradle always uses the {@code toString()} value of the group. The group
     * defaults to the path with dots as separators.</p>
     *
     * @return The group of this project. Never returns null.
     */
    Object getGroup();

    /**
     * <p>Sets the group of this project.</p>
     *
     * @param group The group of this project. Must not be null.
     */
    void setGroup(Object group);

    /**
     * <p>Returns the version of this project. Gradle always uses the {@code toString()} value of the version. The
     * version defaults to {@value #DEFAULT_VERSION}.</p>
     *
     * @return The version of this project. Never returns null.
     */
    Object getVersion();

    /**
     * <p>Sets the version of this project.</p>
     *
     * @param version The version of this project. Must not be null.
     */
    void setVersion(Object version);

    /**
     * <p>Returns the status of this project. Gradle always uses the {@code toString()} value of the status. The status
     * defaults to {@value #DEFAULT_STATUS}.</p>
     *
     * <p>The status of the project is only relevant, if you upload libraries together with a module descriptor. The
     * status specified here, will be part of this module descriptor.</p>
     *
     * @return The status of this project. Never returns null.
     */
    Object getStatus();

    /**
     * Sets the status of this project.
     *
     * @param status The status. Must not be null.
     */
    void setStatus(Object status);

    /**
     * <p>Returns the direct children of this project.</p>
     *
     * @return A map from child project name to child project. Returns an empty map if this project does not have
     *         any children.
     */
    Map<String, BuildProject> getChildProjects();

    /**
     * <p>Sets a property of this project.  This method searches for a property with the given name in the following
     * locations, and sets the property on the first location where it finds the property.</p>
     *
     * <ol>
     *
     * <li>The project object itself.  For example, the <code>rootDir</code> project property.</li>
     *
     * <li>The project's {@link Convention} object.  For example, the <code>srcRootName</code> java plugin
     * property.</li>
     *
     * <li>The project's extra properties.</li>
     *
     * </ol>
     *
     * If the property is not found, a {@link groovy.lang.MissingPropertyException} is thrown.
     *
     * @param name The name of the property
     * @param value The value of the property
     */
    void setProperty(String name, @Nullable Object value); // throws MissingPropertyException;

    /**
     * <p>Returns this project. This method is useful in build files to explicitly access project properties and
     * methods. For example, using <code>project.name</code> can express your intent better than using
     * <code>name</code>. This method also allows you to access project properties from a scope where the property may
     * be hidden, such as, for example, from a method or closure. </p>
     *
     * @return This project. Never returns null.
     */
    BuildProject getProject();

    /**
     * <p>Returns the set containing this project and its subprojects.</p>
     *
     * @return The set of projects.
     */
    Set<BuildProject> getAllprojects();

    /**
     * <p>Returns the set containing the subprojects of this project.</p>
     *
     * @return The set of projects.  Returns an empty set if this project has no subprojects.
     */
    Set<BuildProject> getSubprojects();

    /**
     * <p>Returns the path of this project.  The path is the fully qualified name of the project.</p>
     *
     * @return The path. Never returns null.
     */
    String getPath();

    /**
     * <p>Returns the names of the default tasks of this project. These are used when no tasks names are provided when
     * starting the build.</p>
     *
     * @return The default task names. Returns an empty list if this project has no default tasks.
     */
    List<String> getDefaultTasks();

    /**
     * <p>Sets the names of the default tasks of this project. These are used when no tasks names are provided when
     * starting the build.</p>
     *
     * @param defaultTasks The default task names.
     */
    void setDefaultTasks(List<String> defaultTasks);

    /**
     * <p>Sets the names of the default tasks of this project. These are used when no tasks names are provided when
     * starting the build.</p>
     *
     * @param defaultTasks The default task names.
     */
    void defaultTasks(String... defaultTasks);

    /**
     * <p>Declares that this project has an evaluation dependency on the project with the given path.</p>
     *
     * @param path The path of the project which this project depends on.
     * @return The project which this project depends on.
     * @throws UnknownProjectException If no project with the given path exists.
     */
    BuildProject evaluationDependsOn(String path) throws UnknownProjectException;

    /**
     * <p>Declares that this project has an evaluation dependency on each of its child projects.</p>
     *
     */
    void evaluationDependsOnChildren();

    /**
     * <p>Locates a project by path. If the path is relative, it is interpreted relative to this project.</p>
     *
     * @param path The path.
     * @return The project with the given path. Returns null if no such project exists.
     */
    @Nullable
    BuildProject findProject(String path);

    /**
     * <p>Locates a project by path. If the path is relative, it is interpreted relative to this project.</p>
     *
     * @param path The path.
     * @return The project with the given path. Never returns null.
     * @throws UnknownProjectException If no project with the given path exists.
     */
    BuildProject project(String path) throws UnknownProjectException;

    /**
     * <p>Locates a project by path and configures it using the given action. If the path is relative, it is
     * interpreted relative to this project.</p>
     *
     * @param path The path.
     * @param configureAction The action to use to configure the project.
     * @return The project with the given path. Never returns null.
     * @throws UnknownProjectException If no project with the given path exists.
     *
     * @since 3.4
     */
    BuildProject project(String path, Action<? super BuildProject> configureAction);

    /**
     * <p>Returns a map of the tasks contained in this project, and optionally its subprojects.</p>
     *
     * @param recursive If true, returns the tasks of this project and its subprojects.  If false, returns the tasks of
     * just this project.
     * @return A map from project to a set of tasks.
     */
    Map<BuildProject, Set<Task>> getAllTasks(boolean recursive);

    /**
     * <p>Returns the set of tasks with the given name contained in this project, and optionally its subprojects.
     *
     * <b>NOTE:</b> This is an expensive operation since it requires all projects to be configured.
     * </p>
     *
     * @param name The name of the task to locate.
     * @param recursive If true, returns the tasks of this project and its subprojects. If false, returns the tasks of
     * just this project.
     * @return The set of tasks. Returns an empty set if no such tasks exist in this project.
     */
    Set<Task> getTasksByName(String name, boolean recursive);

    /**
     * <p>The directory containing the project build file.</p>
     *
     * @return The project directory. Never returns null.
     */
    File getProjectDir();

    /**
     * <p>Resolves a file path relative to the project directory of this project. This method converts the supplied path
     * based on its type:</p>
     *
     * <ul>
     *
     * <li>A {@link CharSequence}, including {@link String} or {@link groovy.lang.GString}. Interpreted relative to the project directory. A string that starts with {@code file:} is treated as a file URL.</li>
     *
     * <li>A {@link File}. If the file is an absolute file, it is returned as is. Otherwise, the file's path is
     * interpreted relative to the project directory.</li>
     *
     * <li>A {@link java.nio.file.Path}. The path must be associated with the default provider and is treated the
     * same way as an instance of {@code File}.</li>
     *
     * <li>A {@link java.net.URI} or {@link java.net.URL}. The URL's path is interpreted as the file path. Only {@code file:} URLs are supported.</li>
     *
     * <li>A {@link org.gradle.api.file.Directory} or {@link org.gradle.api.file.RegularFile}.</li>
     *
     * <li>A {@link Provider} of any supported type. The provider's value is resolved recursively.</li>
     *
     * <li>A {@link org.gradle.api.resources.TextResource}.</li>
     *
     * <li>A Groovy {@link Closure} or Kotlin function that returns any supported type. The closure's return value is resolved recursively.</li>
     *
     * <li>A {@link java.util.concurrent.Callable} that returns any supported type. The callable's return value is resolved recursively.</li>
     *
     * </ul>
     *
     * @param path The object to resolve as a File.
     * @return The resolved file. Never returns null.
     */
    File file(Object path);

    /**
     * <p>Resolves a file path relative to the project directory of this project and validates it using the given
     * scheme. See {@link PathValidation} for the list of possible validations.</p>
     *
     * @param path An object which toString method value is interpreted as a relative path to the project directory.
     * @param validation The validation to perform on the file.
     * @return The resolved file. Never returns null.
     * @throws InvalidUserDataException When the file does not meet the given validation constraint.
     */
    File file(Object path, PathValidation validation) throws InvalidUserDataException;

    /**
     * <p>Resolves a file path to a URI, relative to the project directory of this project. Evaluates the provided path
     * object as described for {@link #file(Object)}, with the exception that any URI scheme is supported, not just
     * 'file:' URIs.</p>
     *
     * @param path The object to resolve as a URI.
     * @return The resolved URI. Never returns null.
     */
    URI uri(Object path);

    /**
     * <p>Returns the relative path from the project directory to the given path. The given path object is (logically)
     * resolved as described for {@link #file(Object)}, from which a relative path is calculated.</p>
     *
     * @param path The path to convert to a relative path.
     * @return The relative path. Never returns null.
     * @throws IllegalArgumentException If the given path cannot be relativized against the project directory.
     */
    String relativePath(Object path);

    /**
     * <p>Returns a {@link ConfigurableFileCollection} containing the given files. You can pass any of the following
     * types to this method:</p>
     *
     * <ul> <li>A {@link CharSequence}, including {@link String} or {@link groovy.lang.GString}. Interpreted relative to the project directory, as per {@link #file(Object)}. A string that starts with {@code file:} is treated as a file URL.</li>
     *
     * <li>A {@link File}. Interpreted relative to the project directory, as per {@link #file(Object)}.</li>
     *
     * <li>A {@link java.nio.file.Path}, as per {@link #file(Object)}.</li>
     *
     * <li>A {@link java.net.URI} or {@link java.net.URL}. The URL's path is interpreted as a file path. Only {@code file:} URLs are supported.</li>
     *
     * <li>A {@link org.gradle.api.file.Directory} or {@link org.gradle.api.file.RegularFile}.</li>
     *
     * <li>A {@link java.util.Collection}, {@link Iterable}, or an array that contains objects of any supported type. The elements of the collection are recursively converted to files.</li>
     *
     * <li>A {@link org.gradle.api.file.FileCollection}. The contents of the collection are included in the returned collection.</li>
     *
     * <li>A {@link org.gradle.api.file.FileTree} or {@link org.gradle.api.file.DirectoryTree}. The contents of the tree are included in the returned collection.</li>
     *
     * <li>A {@link Provider} of any supported type. The provider's value is recursively converted to files. If the provider represents an output of a task, that task is executed if the file collection is used as an input to another task.
     *
     * <li>A {@link java.util.concurrent.Callable} that returns any supported type. The return value of the {@code call()} method is recursively converted to files. A {@code null} return value is treated as an empty collection.</li>
     *
     * <li>A Groovy {@link Closure} or Kotlin function that returns any of the types listed here. The return value of the closure is recursively converted to files. A {@code null} return value is treated as an empty collection.</li>
     *
     * <li>A {@link Task}. Converted to the task's output files. The task is executed if the file collection is used as an input to another task.</li>
     *
     * <li>A {@link org.gradle.api.tasks.TaskOutputs}. Converted to the output files the related task. The task is executed if the file collection is used as an input to another task.</li>
     *
     * <li>Anything else is treated as an error.</li>
     *
     * </ul>
     *
     * <p>The returned file collection is lazy, so that the paths are evaluated only when the contents of the file
     * collection are queried. The file collection is also live, so that it evaluates the above each time the contents
     * of the collection is queried.</p>
     *
     * <p>The returned file collection maintains the iteration order of the supplied paths.</p>
     *
     * <p>The returned file collection maintains the details of the tasks that produce the files, so that these tasks are executed if this file collection is used as an input to some task.</p>
     *
     * <p>This method can also be used to create an empty collection, which can later be mutated to add elements.</p>
     *
     * @param paths The paths to the files. May be empty.
     * @return The file collection. Never returns null.
     */
    ConfigurableFileCollection files(Object... paths);

    /**
     * <p>Creates a new {@code ConfigurableFileCollection} using the given paths. The paths are evaluated as per {@link
     * #files(Object...)}. The file collection is configured using the given action. Example:</p>
     * <pre>
     * files "$buildDir/classes" {
     *     builtBy 'compile'
     * }
     * </pre>
     * <p>The returned file collection is lazy, so that the paths are evaluated only when the contents of the file
     * collection are queried. The file collection is also live, so that it evaluates the above each time the contents
     * of the collection is queried.</p>
     *
     * @param paths The contents of the file collection. Evaluated as per {@link #files(Object...)}.
     * @param configureAction The action to use to configure the file collection.
     * @return the configured file tree. Never returns null.
     * @since 3.5
     */
    ConfigurableFileCollection files(Object paths, Action<? super ConfigurableFileCollection> configureAction);

    /**
     * <p>Creates a new {@code ConfigurableFileTree} using the given base directory. The given baseDir path is evaluated
     * as per {@link #file(Object)}.</p>
     *
     * <p>The returned file tree is lazy, so that it scans for files only when the contents of the file tree are
     * queried. The file tree is also live, so that it scans for files each time the contents of the file tree are
     * queried.</p>
     *
     * <pre class='autoTested'>
     * def myTree = fileTree("src")
     * myTree.include "**&#47;*.java"
     * myTree.builtBy "someTask"
     *
     * task copy(type: Copy) {
     *    from myTree
     * }
     * </pre>
     *
     * <p>The order of the files in a {@code FileTree} is not stable, even on a single computer.
     *
     * @param baseDir The base directory of the file tree. Evaluated as per {@link #file(Object)}.
     * @return the file tree. Never returns null.
     */
    ConfigurableFileTree fileTree(Object baseDir);

    /**
     * <p>Creates a new {@code ConfigurableFileTree} using the given base directory. The given baseDir path is evaluated
     * as per {@link #file(Object)}. The action will be used to configure the new file tree. Example:</p>
     *
     * <pre class='autoTested'>
     * def myTree = fileTree('src') {
     *    exclude '**&#47;.data/**'
     *    builtBy 'someTask'
     * }
     *
     * task copy(type: Copy) {
     *    from myTree
     * }
     * </pre>
     *
     * <p>The returned file tree is lazy, so that it scans for files only when the contents of the file tree are
     * queried. The file tree is also live, so that it scans for files each time the contents of the file tree are
     * queried.</p>
     *
     * <p>The order of the files in a {@code FileTree} is not stable, even on a single computer.
     *
     * @param baseDir The base directory of the file tree. Evaluated as per {@link #file(Object)}.
     * @param configureAction Action to configure the {@code ConfigurableFileTree} object.
     * @return the configured file tree. Never returns null.
     * @since 3.5
     */
    ConfigurableFileTree fileTree(Object baseDir, Action<? super ConfigurableFileTree> configureAction);

    /**
     * <p>Creates a new {@code ConfigurableFileTree} using the provided map of arguments.  The map will be applied as
     * properties on the new file tree.  Example:</p>
     *
     * <pre class='autoTested'>
     * def myTree = fileTree(dir:'src', excludes:['**&#47;ignore/**', '**&#47;.data/**'])
     *
     * task copy(type: Copy) {
     *     from myTree
     * }
     * </pre>
     *
     * <p>The returned file tree is lazy, so that it scans for files only when the contents of the file tree are
     * queried. The file tree is also live, so that it scans for files each time the contents of the file tree are
     * queried.</p>
     *
     * <p>The order of the files in a {@code FileTree} is not stable, even on a single computer.
     *
     * @param args map of property assignments to {@code ConfigurableFileTree} object
     * @return the configured file tree. Never returns null.
     */
    ConfigurableFileTree fileTree(Map<String, ?> args);

    /**
     * <p>Creates a new {@code FileTree} which contains the contents of the given ZIP file. The given zipPath path is
     * evaluated as per {@link #file(Object)}. You can combine this method with the {@link #copy(groovy.lang.Closure)}
     * method to unzip a ZIP file.</p>
     *
     * <p>The returned file tree is lazy, so that it scans for files only when the contents of the file tree are
     * queried. The file tree is also live, so that it scans for files each time the contents of the file tree are
     * queried.</p>
     *
     * @param zipPath The ZIP file. Evaluated as per {@link #file(Object)}.
     * @return the file tree. Never returns null.
     */
    FileTree zipTree(Object zipPath);

    /**
     * Creates a new {@code FileTree} which contains the contents of the given TAR file. The given tarPath path can be:
     * <ul>
     *   <li>an instance of {@link org.gradle.api.resources.Resource}</li>
     *   <li>any other object is evaluated as per {@link #file(Object)}</li>
     * </ul>
     *
     * The returned file tree is lazy, so that it scans for files only when the contents of the file tree are
     * queried. The file tree is also live, so that it scans for files each time the contents of the file tree are
     * queried.
     * <p>
     * Unless custom implementation of resources is passed, the tar tree attempts to guess the compression based on the file extension.
     * <p>
     * You can combine this method with the {@link #copy(groovy.lang.Closure)}
     * method to untar a TAR file:
     *
     * <pre class='autoTested'>
     * task untar(type: Copy) {
     *   from tarTree('someCompressedTar.gzip')
     *
     *   //tar tree attempts to guess the compression based on the file extension
     *   //however if you must specify the compression explicitly you can:
     *   from tarTree(resources.gzip('someTar.ext'))
     *
     *   //in case you work with unconventionally compressed tars
     *   //you can provide your own implementation of a ReadableResource:
     *   //from tarTree(yourOwnResource as ReadableResource)
     *
     *   into 'dest'
     * }
     * </pre>
     *
     * @param tarPath The TAR file or an instance of {@link org.gradle.api.resources.Resource}.
     * @return the file tree. Never returns null.
     */
    FileTree tarTree(Object tarPath);

    /**
     * Creates a {@code Provider} implementation based on the provided value.
     *
     * @param value The {@code java.util.concurrent.Callable} use to calculate the value.
     * @return The provider. Never returns null.
     * @throws org.gradle.api.InvalidUserDataException If the provided value is null.
     * @see org.gradle.api.provider.ProviderFactory#provider(Callable)
     * @since 4.0
     */
    <T> Provider<T> provider(Callable<T> value);

//    /**
//     * Provides access to methods to create various kinds of {@link Provider} instances.
//     *
//     * @since 4.0
//     */
//    ProviderFactory getProviders();
//
//    /**
//     * Provides access to methods to create various kinds of model objects.
//     *
//     * @since 4.0
//     */
//    ObjectFactory getObjects();

    /**
     * Creates a directory and returns a file pointing to it.
     *
     * @param path The path for the directory to be created. Evaluated as per {@link #file(Object)}.
     * @return the created directory
     * @throws org.gradle.api.InvalidUserDataException If the path points to an existing file.
     */
    File mkdir(Object path);

    /**
     * Deletes files and directories.
     * <p>
     * This will not follow symlinks. If you need to follow symlinks too use {@link #delete(Action)}.
     *
     * @param paths Any type of object accepted by {@link org.gradle.api.Project#files(Object...)}
     * @return true if anything got deleted, false otherwise
     */
    boolean delete(Object... paths);

    /**
     * Deletes the specified files.  The given action is used to configure a {@link DeleteSpec}, which is then used to
     * delete the files.
     * <p>Example:
     * <pre>
     * project.delete {
     *     delete 'somefile'
     *     followSymlinks = true
     * }
     * </pre>
     *
     * @param action Action to configure the DeleteSpec
     * @return {@link WorkResult} that can be used to check if delete did any work.
     */
    WorkResult delete(Action<? super DeleteSpec> action);

    /**
     * <p>Converts a name to an absolute project path, resolving names relative to this project.</p>
     *
     * @param path The path to convert.
     * @return The absolute path.
     */
    String absoluteProjectPath(String path);

    /**
     * <p>Converts a name to a project path relative to this project.</p>
     *
     * @param path The path to convert.
     * @return The relative path.
     */
    String relativeProjectPath(String path);

    /**
     * <p>Compares the nesting level of this project with another project of the multi-project hierarchy.</p>
     *
     * @param otherProject The project to compare the nesting level with.
     * @return a negative integer, zero, or a positive integer as this project has a nesting level less than, equal to,
     *         or greater than the specified object.
     * @see #getDepth()
     */
    int depthCompare(BuildProject otherProject);

    /**
     * <p>Returns the nesting level of a project in a multi-project hierarchy. For single project builds this is always
     * 0. In a multi-project hierarchy 0 is returned for the root project.</p>
     */
    int getDepth();

    /**
     * <p>Returns the tasks of this project.</p>
     *
     * @return the tasks of this project.
     */
    TaskContainer getTasks();

    /**
     * <p>Configures the sub-projects of this project</p>
     *
     * <p>This method executes the given {@link Action} against the sub-projects of this project.</p>
     *
     * @param action The action to execute.
     */
    void subprojects(Action<? super BuildProject> action);

    /**
     * <p>Configures this project and each of its sub-projects.</p>
     *
     * <p>This method executes the given {@link Action} against this project and each of its sub-projects.</p>
     *
     * @param action The action to execute.
     */
    void allprojects(Action<? super BuildProject> action);


    /**
     * Adds an action to execute immediately before this project is evaluated.
     *
     * @param action the action to execute.
     */
    void beforeEvaluate(Action<? super BuildProject> action);

    /**
     * Adds an action to execute immediately after this project is evaluated.
     *
     * @param action the action to execute.
     */
    void afterEvaluate(Action<? super BuildProject> action);

    /**
     * <p>Determines if this project has the given property. See <a href="#properties">here</a> for details of the
     * properties which are available for a project.</p>
     *
     * @param propertyName The name of the property to locate.
     * @return True if this project has the given property, false otherwise.
     */
    boolean hasProperty(String propertyName);

    /**
     * <p>Returns the properties of this project. See <a href="#properties">here</a> for details of the properties which
     * are available for a project.</p>
     *
     * @return A map from property name to value.
     */
    Map<String, ?> getProperties();

    /**
     * <p>Returns the value of the given property.  This method locates a property as follows:</p>
     *
     * <ol>
     *
     * <li>If this project object has a property with the given name, return the value of the property.</li>
     *
     * <li>If this project has an extension with the given name, return the extension.</li>
     *
     * <li>If this project's convention object has a property with the given name, return the value of the
     * property.</li>
     *
     * <li>If this project has an extra property with the given name, return the value of the property.</li>
     *
     * <li>If this project has a task with the given name, return the task.</li>
     *
     * <li>Search up through this project's ancestor projects for a convention property or extra property with the
     * given name.</li>
     *
     * <li>If not found, a {@link MissingPropertyException} is thrown.</li>
     *
     * </ol>
     *
     * @param propertyName The name of the property.
     * @return The value of the property, possibly null.
     * @throws MissingPropertyException When the given property is unknown.
     * @see Project#findProperty(String)
     */
    @Nullable
    Object property(String propertyName); // throws MissingPropertyException;

    /**
     * <p>Returns the value of the given property or null if not found.
     * This method locates a property as follows:</p>
     *
     * <ol>
     *
     * <li>If this project object has a property with the given name, return the value of the property.</li>
     *
     * <li>If this project has an extension with the given name, return the extension.</li>
     *
     * <li>If this project's convention object has a property with the given name, return the value of the
     * property.</li>
     *
     * <li>If this project has an extra property with the given name, return the value of the property.</li>
     *
     * <li>If this project has a task with the given name, return the task.</li>
     *
     * <li>Search up through this project's ancestor projects for a convention property or extra property with the
     * given name.</li>
     *
     * <li>If not found, null value is returned.</li>
     *
     * </ol>
     *
     * @param propertyName The name of the property.
     * @since 2.13
     * @return The value of the property, possibly null or null if not found.
     * @see Project#property(String)
     */
    @Nullable
    Object findProperty(String propertyName);

    /**
     * <p>Returns the logger for this project. You can use this in your build file to write log messages.</p>
     *
     * @return The logger. Never returns null.
     */
    Logger getLogger();

    /**
     * Configures a collection of objects via an action.
     *
     * @param objects The objects to configure
     * @param configureAction The action to apply to each object
     * @return The configured objects.
     */
    <T> Iterable<T> configure(Iterable<T> objects, Action<? super T> configureAction);

    Path getProjectPath();
}
