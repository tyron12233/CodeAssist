package com.tyron.builder.compiler2.api;

import androidx.annotation.Nullable;

import com.tyron.builder.log.ILogger;
import com.tyron.builder.project.api.Project;

import java.io.File;
import java.util.List;

public interface Task {

    /**
     * <p>Returns the sequence of {@link Action} objects which will be executed by this task, in the order of
     * execution.</p>
     *
     * @return The task actions in the order they are executed. Returns an empty list if this task has no actions.
     */
    List<Action<? super Task>> getActions();

    /**
     * <p>Sets the sequence of {@link Action} objects which will be executed by this task.</p>
     *
     * @param actions The actions.
     */
    void setActions(List<Action<? super Task>> actions);

    /**
     * <p>Returns the name of this task. The name uniquely identifies the task within its {@link Project}.</p>
     *
     * @return The name of the task. Never returns null.
     */
    String getName();

    /**
     * <p>Adds the given {@link Action} to the beginning of this task's action list.</p>
     *
     * @param action The action to add
     * @return the task object this method is applied to
     */
    Task doFirst(Action<? super Task> action);

    /**
     * <p>Adds the given {@link Action} to the beginning of this task's action list.</p>
     *
     * @param actionName An arbitrary string that is used for logging.
     * @param action The action to add
     * @return the task object this method is applied to
     *
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
     * <p>Returns the logger for this task. You can use this in your build file to write log messages.</p>
     *
     * @return The logger. Never returns null.
     */
    ILogger getLogger();

    /**
     * Returns the description of this task.
     *
     * @return the description. May return null.
     */
    @Nullable
    String getDescription();

    /**
     * Sets a description for this task. This should describe what the task does to the user of the build. The
     * description will be displayed when <code>task</code> is called.
     *
     * @param description The description of the task. Might be null.
     */
    void setDescription(@Nullable String description);

    /**
     * <p>Returns a directory which this task can use to write temporary files to. Each task instance is provided with a
     * separate temporary directory. There are no guarantees that the contents of this directory will be kept beyond the
     * execution of the task.</p>
     *
     * @return The directory. Never returns null. The directory will already exist.
     */
    File getTemporaryDir();
}
