package com.tyron.builder.configuration.project;

import static com.tyron.builder.api.internal.GUtil.unchecked;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.ProjectEvaluationListener;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.configuration.project.ProjectConfigureAction;
import com.tyron.builder.api.internal.GUtil;
import com.tyron.builder.api.internal.UncheckedException;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.util.GFileUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import bsh.EvalError;
import bsh.Interpreter;

public class BuildScriptProcessor implements ProjectConfigureAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildScriptProcessor.class);

    private static final List<Class<?>> DEFAULT_IMPORTED_CLASSES = Lists.newArrayList(
            Action.class, Task.class
    );

    @Override
    public void execute(ProjectInternal project) {
        ListenerManager listenerManager = project.getServices().get(ListenerManager.class);
        ProjectEvaluationListener broadcaster = listenerManager.getBroadcaster(ProjectEvaluationListener.class);
        broadcaster.beforeEvaluate(project);

        File buildScript = project.getBuildFile();
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Evaluating {} using {}.", project, buildScript.getName());
        }
        unchecked(() -> {
            Interpreter interpreter = new Interpreter();
            for (Class<?> clazz : DEFAULT_IMPORTED_CLASSES) {
                interpreter.eval("import " + clazz.getName());
            }
            interpreter.set("project", project);
            interpreter.eval(GFileUtils.readFileToString(buildScript));
        });
    }
}
