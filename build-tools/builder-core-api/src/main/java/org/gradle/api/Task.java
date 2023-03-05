package org.gradle.api;

import org.gradle.api.logging.Logger;
import org.gradle.api.logging.LoggingManager;
import org.gradle.api.plugins.Convention;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.Property;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskDependency;
import org.gradle.api.tasks.TaskDestroyables;
import org.gradle.api.tasks.TaskInputs;
import org.gradle.api.tasks.TaskLocalState;
import org.gradle.api.tasks.TaskOutputs;
import org.gradle.api.tasks.TaskState;

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import groovy.lang.Closure;

public interface Task extends Comparable<Task>, ExtensionAware {

    String TASK_NAME = "name";

    String TASK_DESCRIPTION = "description";

    String TASK_GROUP = "group";

    String TASK_TYPE = "type";

    String TASK_DEPENDS_ON = "dependsOn";

    String TASK_OVERWRITE = "overwrite";

    String TASK_ACTION = "action";

    /**
     * Constructor arguments for the Task
     *
     * @since 4.7
     */
    String TASK_CONSTRUCTOR_ARGS = "constructorArgs";

    /**
     * A {@link org.gradle.api.Namer} namer for tasks that returns {@link #getName()}.
     */
    class Namer implements org.gradle.api.Namer<Task> {
        @Override
        public String determineName(Task c) {
            return c.getName();
        }
    }

    @Incubating
    @Internal
    void doNotTrackState(String reasonNotToTrackState);

    /**
     * Specifies that this task is not compatible with the configuration cache.
     *
     * <p>
     * Configuration cache problems found in the task will be reported but won't cause the build to fail.
     * </p>
     *
     * <p>
     * The presence of incompatible tasks in the task graph will cause the configuration state to be discarded
     * at the end of the build unless the global {@code configuration-cache-problems} option is set to {@code warn},
     * in which case the configuration state would still be cached in a best-effort manner as usual for the option.
     * </p>
     *
     * <p>
     * <b>IMPORTANT:</b> This setting doesn't affect how Gradle treats problems found in other tasks also present in the task graph and those
     * could still cause the build to fail.
     * </p>
     *
     * @since 7.4
     */
    @Incubating
    void notCompatibleWithConfigurationCache(String reason);

    /**
     * Returns the name of this task. This name uniquely identifies the task within its Project
     * @return The name of this task, never returns null.
     */
    String getName();

    /**
     * @return The sequence of {@link Action} objects which will be executed by this task, in the
     * order of execution
     */
    List<Action<? super Task>> getActions();

    /**
     * Sets the sequence of {@link Action} objects which will be executed by this task.
     * @param actions The actions
     */
    void setActions(List<Action<? super Task>> actions);

    /**
     * <p>Returns a {@link TaskDependency} which contains all the tasks that this task depends on.</p>
     *
     * <p>Calling this method from a task action is not supported when configuration caching is enabled.</p>
     *
     * @return The dependencies of this task. Never returns null.
     */
    @Internal
    TaskDependency getTaskDependencies();

    Task dependsOn(Object... paths);

    default Task dependsOn(Object paths) {
        return this.dependsOn(new Object[]{paths});
    }

    /**
     * <p>Returns the dependencies of this task.</p>
     *
     * @return The dependencies of this task. Returns an empty set if this task has no dependencies.
     */
    @Internal
    Set<Object> getDependsOn();

    /**
     * <p>Sets the dependencies of this task.
     *
     * @param dependsOnTasks The set of task paths.
     */
    void setDependsOn(Iterable<?> dependsOnTasks);

    /**
     * <p>Execute the task only if the given spec is satisfied. The spec will be evaluated at task execution time, not
     * during configuration. If the Spec is not satisfied, the task will be skipped.</p>
     *
     * <p>You may add multiple such predicates. The task is skipped if any of the predicates return false.</p>
     *
     * <p>Typical usage (from Java):</p>
     * <pre>myTask.onlyIf(new Spec&lt;Task&gt;() {
     *    boolean isSatisfiedBy(Task task) {
     *       return isProductionEnvironment();
     *    }
     * });
     * </pre>
     *
     * @param onlyIfSpec specifies if a task should be run
     */
    void onlyIf(Spec<? super Task> onlyIfSpec);

    /**
     * <p>Execute the task only if the given spec is satisfied. The spec will be evaluated at task execution time, not
     * during configuration. If the Spec is not satisfied, the task will be skipped.</p>
     *
     * <p>The given predicate replaces all such predicates for this task.</p>
     *
     * @param onlyIfSpec specifies if a task should be run
     */
    void setOnlyIf(Spec<? super Task> onlyIfSpec);

    /**
     * Returns the execution state of this task. This provides information about the execution of this task, such as
     * whether it has executed, been skipped, has failed, etc.
     *
     * @return The execution state of this task. Never returns null.
     */
    TaskState getState();

    /**
     * Sets whether the task actually did any work.  Most built-in tasks will set this automatically, but
     * it may be useful to manually indicate this for custom user tasks.
     * @param didWork indicates if the task did any work
     */
    void setDidWork(boolean didWork);

    /**
     * <p>Checks if the task actually did any work.  Even if a Task executes, it may determine that it has nothing to
     * do.  For example, a compilation task may determine that source files have not changed since the last time a the
     * task was run.</p>
     *
     * @return true if this task did any work
     */
    @Internal
    boolean getDidWork();

    /**
     * <p>Returns the path of the task, which is a fully qualified name for the task. The path of a task is the path of
     * its {@link Project} plus the name of the task, separated by <code>:</code>.</p>
     *
     * @return the path of the task, which is equal to the path of the project plus the name of the task.
     */
    String getPath();

    /**
     * <p>Adds the given {@link Action} to the beginning of this task's action list.</p>
     *
     * @param action The action to add
     * @return the task object this method is applied to
     */
    Task doFirst(Action<? super Task> action);

    /**
     * <p>Adds the given closure to the beginning of this task's action list. The closure is passed this task as a
     * parameter when executed.</p>
     *
     * @param action The action closure to execute.
     * @return This task.
     */
    Task doFirst(Closure action);

    /**
     * <p>Adds the given {@link Action} to the beginning of this task's action list.</p>
     *
     * @param actionName An arbitrary string that is used for logging.
     * @param action The action to add
     * @return the task object this method is applied to
     *
     * @since 4.2
     */
    Task doFirst(String actionName, Action<? super Task> action);

    /**
     * <p>Adds the given {@link Action} to the end of this task's action list.</p>
     *
     * @param action The action to add.
     * @return the task object this method is applied to
     */
    Task doLast(Action<? super Task> action);

    /**
     * <p>Adds the given closure to the end of this task's action list.  The closure is passed this task as a parameter
     * when executed.</p>
     *
     * @param action The action closure to execute.
     * @return This task.
     */
    Task doLast(Closure action);

    /**
     * <p>Adds the given {@link Action} to the end of this task's action list.</p>
     *
     * @param actionName An arbitrary string that is used for logging.
     * @param action The action to add.
     * @return the task object this method is applied to
     *
     * @since 4.2
     */
    Task doLast(String actionName, Action<? super Task> action);

    /**
     * <p>Returns if this task is enabled or not.</p>
     *
     * @see #setEnabled(boolean)
     */
    boolean getEnabled();

    /**
     * <p>Set the enabled state of a task. If a task is disabled none of the its actions are executed. Note that
     * disabling a task does not prevent the execution of the tasks which this task depends on.</p>
     *
     * @param enabled The enabled state of this task (true or false)
     */
    void setEnabled(boolean enabled);

    /**
     * <p>Applies the statements of the closure against this task object. The delegate object for the closure is set to
     * this task.</p>
     *
     * @param configureClosure The closure to be applied (can be null).
     * @return This task
     */
    Task configure(Closure configureClosure);

    /**
     * <p>Returns the {@link Convention} object for this task. A {@link Plugin} can use the convention object to
     * contribute properties and methods to this task.</p>
     *
     * @return The convention object. Never returns null.
     * @deprecated The concept of conventions is deprecated. Use extensions if possible.
     * @see ExtensionAware#getExtensions()
     */
    @Internal
    @Deprecated
    Convention getConvention();

    /**
     * Returns the description of this task.
     *
     * @return the description. May return null.
     */
    @Internal
    String getDescription();

    /**
     * Sets a description for this task. This should describe what the task does to the user of the build. The
     * description will be displayed when <code>gradle tasks</code> is called.
     *
     * @param description The description of the task. Might be null.
     */
    void setDescription(String description);

    /**
     * Returns the task group which this task belongs to. The task group is used in reports and user interfaces to
     * group related tasks together when presenting a list of tasks to the user.
     *
     * @return The task group for this task. Might be null.
     */
    @Internal
    @Nullable
    String getGroup();

    /**
     * Sets the task group which this task belongs to. The task group is used in reports and user interfaces to
     * group related tasks together when presenting a list of tasks to the user.
     *
     * @param group The task group for this task. Can be null.
     */
    void setGroup(String group);


    /**
     * <p>Returns the inputs of this task.</p>
     *
     * @return The inputs. Never returns null.
     */
    @Internal
    TaskInputs getInputs();

    /**
     * <p>Returns the outputs of this task.</p>
     *
     * @return The outputs. Never returns null.
     */
    @Internal
    TaskOutputs getOutputs();

    /**
     * <p>Returns the destroyables of this task.</p>
     * @return The destroyables.  Never returns null.
     *
     * @since 4.0
     */
    @Internal
    TaskDestroyables getDestroyables();

    /**
     * Returns the local state of this task.
     *
     * @since 4.3
     */
    @Internal
    TaskLocalState getLocalState();

    /**
     * <p>Returns a directory which this task can use to write temporary files to. Each task instance is provided with a
     * separate temporary directory. There are no guarantees that the contents of this directory will be kept beyond the
     * execution of the task.</p>
     *
     * @return The directory. Never returns null. The directory will already exist.
     */
    @Internal
    File getTemporaryDir();


    /**
     * <p>Specifies that this task must run after all of the supplied tasks.</p>
     *
     * <pre class='autoTested'>
     * task taskY {
     *     mustRunAfter "taskX"
     * }
     * </pre>
     *
     * <p>For each supplied task, this action adds a task 'ordering', and does not specify a 'dependency' between the tasks.
     * As such, it is still possible to execute 'taskY' without first executing the 'taskX' in the example.</p>
     *
     * <p>See <a href="#dependencies">here</a> for a description of the types of objects which can be used to specify
     * an ordering relationship.</p>
     *
     * @param paths The tasks this task must run after.
     *
     * @return the task object this method is applied to
     */
    Task mustRunAfter(Object... paths);

    default Task mustRunAfter(Object path) {
        return mustRunAfter(new Object[]{path});
    }

    /**
     * <p>Specifies the set of tasks that this task must run after.</p>
     *
     * <pre class='autoTested'>
     * task taskY {
     *     mustRunAfter = ["taskX1", "taskX2"]
     * }
     * </pre>
     *
     * <p>For each supplied task, this action adds a task 'ordering', and does not specify a 'dependency' between the tasks.
     * As such, it is still possible to execute 'taskY' without first executing the 'taskX' in the example.</p>
     *
     * <p>See <a href="#dependencies">here</a> for a description of the types of objects which can be used to specify
     * an ordering relationship.</p>
     *
     * @param mustRunAfter The set of task paths this task must run after.
     */
    void setMustRunAfter(Iterable<?> mustRunAfter);

    /**
     * <p>Returns tasks that this task must run after.</p>
     *
     * @return The tasks that this task must run after. Returns an empty set if this task has no tasks it must run after.
     */
    TaskDependency getMustRunAfter();

    /**
     * <p>Adds the given finalizer tasks for this task.</p>
     *
     * <pre class='autoTested'>
     * task taskY {
     *     finalizedBy "taskX"
     * }
     * </pre>
     *
     * <p>See <a href="#dependencies">here</a> for a description of the types of objects which can be used to specify
     * a finalizer task.</p>
     *
     * @param paths The tasks that finalize this task.
     *
     * @return the task object this method is applied to
     */
    Task finalizedBy(Object... paths);

    default Task finalizedBy(Object path) {
        return finalizedBy(new Object[]{path});
    }

    /**
     * <p>Specifies the set of finalizer tasks for this task.</p>
     *
     * <pre class='autoTested'>
     * task taskY {
     *     finalizedBy = ["taskX1", "taskX2"]
     * }
     * </pre>
     *
     * <p>See <a href="#dependencies">here</a> for a description of the types of objects which can be used to specify
     * a finalizer task.</p>
     *
     * @param finalizedBy The tasks that finalize this task.
     */
    void setFinalizedBy(Iterable<?> finalizedBy);

    /**
     * <p>Returns tasks that finalize this task.</p>
     *
     * @return The tasks that finalize this task. Returns an empty set if there are no finalising tasks for this task.
     */
    TaskDependency getFinalizedBy();

    /**
     * <p>Specifies that this task should run after all of the supplied tasks.</p>
     *
     * <pre class='autoTested'>
     * task taskY {
     *     shouldRunAfter "taskX"
     * }
     * </pre>
     *
     * <p>For each supplied task, this action adds a task 'ordering', and does not specify a 'dependency' between the tasks.
     * As such, it is still possible to execute 'taskY' without first executing the 'taskX' in the example.</p>
     *
     * <p>See <a href="#dependencies">here</a> for a description of the types of objects which can be used to specify
     * an ordering relationship.</p>
     *
     * @param paths The tasks this task should run after.
     *
     * @return the task object this method is applied to
     */
    TaskDependency shouldRunAfter(Object... paths);

    /**
     * <p>Specifies the set of tasks that this task should run after.</p>
     *
     * <pre class='autoTested'>
     * task taskY {
     *     shouldRunAfter = ["taskX1", "taskX2"]
     * }
     * </pre>
     *
     * <p>For each supplied task, this action adds a task 'ordering', and does not specify a 'dependency' between the tasks.
     * As such, it is still possible to execute 'taskY' without first executing the 'taskX' in the example.</p>
     *
     * <p>See <a href="#dependencies">here</a> for a description of the types of objects which can be used to specify
     * an ordering relationship.</p>
     *
     * @param shouldRunAfter The set of task paths this task should run after.
     */
    void setShouldRunAfter(Iterable<?> shouldRunAfter);


    /**
     * <p>Returns tasks that this task should run after.</p>
     *
     * @return The tasks that this task should run after. Returns an empty set if this task has no tasks it must run after.
     */
    TaskDependency getShouldRunAfter();

    /**
     * <p>The timeout of this task.</p>
     *
     * <pre class='autoTested'>
     *   task myTask {
     *       timeout = Duration.ofMinutes(10)
     *   }
     * </pre>
     *
     * <p>
     * The Thread executing this task will be interrupted if the task takes longer than the specified amount of time to run.
     * In order for a task to work properly with this feature, it needs to react to interrupts and must clean up any resources it opened.
     * </p>
     * <p>By default, tasks never time out.</p>
     *
     * @since 5.0
     */
    @Internal
    @Optional
    Property<Duration> getTimeout();


    Project getProject();

    /**
     * <p>Returns the logger for this task. You can use this in your build file to write log messages.</p>
     *
     * @return The logger. Never returns null.
     */
    @Internal
    Logger getLogger();

    /**
     * Returns the {@link LoggingManager} which can be used to receive logging and to control the
     * standard output/error capture for this task. By default, System.out is redirected to the Gradle logging system at
     * the QUIET log level, and System.err is redirected at the ERROR log level.
     *
     * @return the LoggingManager. Never returns null.
     */
    @Internal
    LoggingManager getLogging();
}
