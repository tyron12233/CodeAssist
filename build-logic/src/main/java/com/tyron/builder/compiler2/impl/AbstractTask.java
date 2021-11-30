package com.tyron.builder.compiler2.impl;

import androidx.annotation.Nullable;

import com.tyron.builder.compiler2.api.Action;
import com.tyron.builder.compiler2.api.Describable;
import com.tyron.builder.compiler2.api.Task;
import com.tyron.builder.log.ILogger;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AbstractTask implements Task {

    private String name;
    private String description;
    private boolean enabled;
    private List<Action<? super Task>> actions;

    @Override
    public List<Action<? super Task>> getActions() {
        if (actions == null) {
            actions = new ArrayList<>();
        }
        return actions;
    }

    @Override
    public void setActions(List<Action<? super Task>> actions) {
        this.actions.clear();
        for (Action<? super Task> action : actions) {
            doLast(action);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Task doFirst(Action<? super Task> action) {
        return doFirst("doFirst {} action", action);
    }

    @Override
    public Task doFirst(String actionName, Action<? super Task> action) {
        getActions().add(0, wrap(action, actionName));
        return this;
    }

    @Override
    public Task doLast(Action<? super Task> action) {
        return doLast("doLast {} action", action);
    }

    @Override
    public Task doLast(String actionName, Action<? super Task> action) {
        if (action == null) {
            throw new IllegalArgumentException("Action must not be null");
        }
        getActions().add(wrap(action, actionName));
        return this;
    }

    @Override
    public boolean getEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public ILogger getLogger() {
        return null;
    }

    @Nullable
    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public void setDescription(@Nullable String description) {
        this.description = description;
    }

    @Override
    public File getTemporaryDir() {
        return null;
    }

    private TaskActionWrapper wrap(final Action<? super Task> action) {
        return wrap(action, "Unnamed action");
    }

    private TaskActionWrapper wrap(final Action<? super Task> action, String name) {
        return new TaskActionWrapper(action, name);
    }

    private static class TaskActionWrapper implements Action<Task>, Describable {
        private final Action<? super Task> action;
        private final String maybeActionName;
        /**
         * The <i>action name</i> is used to construct a human readable name for
         * the actions to be used in progress logging. It is only used if
         * the wrapped action does not already implement {@link Describable}.
         */
        public TaskActionWrapper(Action<? super Task> action, String maybeActionName) {
            this.action = action;
            this.maybeActionName = maybeActionName;
        }

        @Override
        public void execute(Task task) {
            ClassLoader original = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(action.getClass().getClassLoader());
            try {
                action.execute(task);
            } finally {
                Thread.currentThread().setContextClassLoader(original);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TaskActionWrapper)) {
                return false;
            }

            TaskActionWrapper that = (TaskActionWrapper) o;
            return action.equals(that.action);
        }

        @Override
        public int hashCode() {
            return action.hashCode();
        }


        @Override
        public String getDisplayName() {
            if (action instanceof Describable) {
                return ((Describable) action).getDisplayName();
            }
            return "Execute " + maybeActionName;
        }
    }
}
