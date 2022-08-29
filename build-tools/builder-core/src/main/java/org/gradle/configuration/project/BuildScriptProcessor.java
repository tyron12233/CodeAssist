package org.gradle.configuration.project;

import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.configuration.ScriptPlugin;
import org.gradle.configuration.ScriptPluginFactory;
import org.gradle.internal.time.Time;
import org.gradle.internal.time.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BuildScriptProcessor implements ProjectConfigureAction {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildScriptProcessor.class);
    private final ScriptPluginFactory configurerFactory;

    public BuildScriptProcessor(ScriptPluginFactory configurerFactory) {
        this.configurerFactory = configurerFactory;
    }

    @Override
    public void execute(final ProjectInternal project) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("Evaluating {} using {}.", project, project.getBuildScriptSource().getDisplayName());
        }
        final Timer clock = Time.startTimer();
        try {
            final ScriptPlugin configurer = configurerFactory.create(project.getBuildScriptSource(), project.getBuildscript(), project.getClassLoaderScope(), project.getBaseClassLoaderScope(), true);
            project.getOwner().applyToMutableState(configurer::apply);
        } finally {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Timing: Running the build script took {}", clock.getElapsed());
            }
        }
    }
}