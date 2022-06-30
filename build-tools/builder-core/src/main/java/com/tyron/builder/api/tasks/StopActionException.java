package com.tyron.builder.api.tasks;

import com.tyron.builder.api.BuildException;

/**
 * <p>A <code>StopActionException</code> is be thrown by a task {@link com.tyron.builder.api.Action} or task action closure to
 * stop its own execution and to start execution of the task's next action. An action can usually be stopped by just
 * calling return inside the action closure. But if the action works with helper methods that can lead to redundant
 * code. For example:</p>
 *
 * <pre>
 *     List existentSourceDirs = HelperUtil.getExistentSourceDirs()
 *     if (!existentSourceDirs) {return}
 * </pre>
 * <p>If the <code>getExistentSourceDirs()</code> throws a <code>StopActionException</code> instead, the tasks does not
 * need the if statement.</p>
 *
 * <p>Note that throwing this exception does not fail the execution of the task or the build.</p>
 */
public class StopActionException extends BuildException {
    public StopActionException() {
        super();
    }

    public StopActionException(String message) {
        super(message);
    }
}
