package com.tyron.builder.configuration.project;

import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.api.ProjectEvaluationListener;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.internal.event.ListenerManager;
import com.tyron.builder.internal.exceptions.LocationAwareException;
import com.tyron.builder.util.internal.GFileUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import bsh.EvalError;
import bsh.Interpreter;
import bsh.TargetError;

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

        Interpreter interpreter = new Interpreter();

        try {
            for (Class<?> clazz : DEFAULT_IMPORTED_CLASSES) {
                interpreter.eval("import " + clazz.getName());
            }
            interpreter.set("project", project);


            Method[] declaredMethods = BuildProject.class.getDeclaredMethods();
            for (Method method : declaredMethods) {
                String parameters = printParameters(method);
                interpreter.eval(method.getName() + "(" +  parameters + ") {" +
                                 "project." + method.getName() + "(" + parameters + "); }");
            }
        } catch (EvalError e) {
            // this should not happen, if it does just log it
            LOGGER.error("Failed to inject variables and classes.", e);
        }

        try {
            interpreter.eval(GFileUtils.readFileToString(buildScript));
        } catch (EvalError error) {
            Throwable cause;
            if (error instanceof TargetError) {
                cause = ((TargetError) error).getTarget();
            } else {
                cause = error;
            }
            throw new LocationAwareException(cause, buildScript.getPath(), error.getErrorLineNumber());
        }
    }

    private String printParameters(Method method) {
        return Arrays.stream(method.getParameters()).map(Parameter::getName)
                .collect(Collectors.joining(", "));
    }
}
