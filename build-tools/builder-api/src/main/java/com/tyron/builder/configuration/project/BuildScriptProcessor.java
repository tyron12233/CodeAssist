package com.tyron.builder.configuration.project;

import com.tyron.builder.api.ProjectEvaluationListener;
import com.tyron.builder.api.configuration.project.ProjectConfigureAction;
import com.tyron.builder.api.internal.event.ListenerManager;
import com.tyron.builder.api.internal.project.ProjectInternal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildScriptProcessor implements ProjectConfigureAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildScriptProcessor.class);

    @Override
    public void execute(ProjectInternal project) {
//        if (LOGGER.isInfoEnabled()) {
//            LOGGER.info("Evaluating {} using {}.", project, project.getBuildScriptSource().getDisplayName());
//        }
        ListenerManager listenerManager = project.getServices().get(ListenerManager.class);
        ProjectEvaluationListener broadcaster = listenerManager.getBroadcaster(ProjectEvaluationListener.class);
        broadcaster.beforeEvaluate(project);
    }
}
