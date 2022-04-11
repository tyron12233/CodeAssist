package com.tyron.builder.api;

import com.tyron.builder.api.internal.logging.services.DefaultStyledTextOutputFactory;
import com.tyron.builder.api.internal.operations.MultipleBuildOperationFailures;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.tasks.TaskExecutor;
import com.tyron.builder.api.internal.time.Time;
import com.tyron.builder.api.logging.LogLevel;
import com.tyron.builder.api.logging.configuration.ConsoleOutput;
import com.tyron.builder.api.logging.configuration.LoggingConfiguration;
import com.tyron.builder.api.logging.configuration.ShowStacktrace;
import com.tyron.builder.api.logging.configuration.WarningMode;
import com.tyron.builder.api.project.BuildProject;
import com.tyron.builder.api.internal.project.ProjectBuilderImpl;
import com.tyron.builder.internal.buildevents.BuildExceptionReporter;
import com.tyron.common.TestUtil;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.impl.SimpleLogger;

import java.io.File;
import java.util.List;

public abstract class TestTaskExecutionCase {

    protected ProjectInternal project;


    @Before
    public void setup() throws Exception {
        System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "Debug");

        File resourcesDirectory = TestUtil.getResourcesDirectory();
        File userHomeDir = new File(resourcesDirectory, ".gradle");
        File projectDir = new File(resourcesDirectory, "TestProject");

        ProjectBuilderImpl projectBuilder = new ProjectBuilderImpl();
        project = projectBuilder.createProject("TestProject", projectDir, userHomeDir);
    }

    protected String getProjectName() {
        return "TestProject";
    }

    protected void registerGlobalServices(ServiceRegistration serviceRegistration) {

    }

    @Test
    public void test() {
        evaluateProject(project, this::evaluateProject);

        if (project.getState().hasFailure()) {
            project.getState().rethrowFailure();
        }

        executeProject(project, getTasksToExecute().toArray(new String[0]));
    }

    public abstract void evaluateProject(BuildProject project);

    public abstract List<String> getTasksToExecute();

    /**
     * Used to evaluate the project and mutate its state
     * @param project the project to evaluate
     * @param evaluationAction the action to run that will evaluate the project
     */
    private void evaluateProject(ProjectInternal project, Action<BuildProject> evaluationAction) {
        project.getState().toBeforeEvaluate();
        project.getState().toEvaluate();
        try {
            evaluationAction.execute(project);
        } catch (Throwable e) {
            project.getState()
                    .failed(new ProjectConfigurationException("Failed to evaluate project.", e));
        }

        project.getState().toAfterEvaluate();

        if (!project.getState().hasFailure()) {
            project.getState().configured();
        }
    }

    /**
     * Runs the given tasks on the project
     * @param project The project
     * @param taskNames The names of the tasks to run
     */
    private void executeProject(ProjectInternal project, String... taskNames) {
        TaskExecutor taskExecutor = new TaskExecutor(project);
        taskExecutor.execute(taskNames);
        List<Throwable> failures = taskExecutor.getFailures();

        if (failures.isEmpty()) {
            return;
        }

        throwFailures(failures);

        throw new AssertionError(failures);
    }

    private void throwFailures(List<Throwable> throwables) {
        BuildExceptionReporter buildExceptionReporter =
                new BuildExceptionReporter(new DefaultStyledTextOutputFactory(event -> {

                }, Time.clock()), new LoggingConfiguration() {
                    @Override
                    public LogLevel getLogLevel() {
                        return LogLevel.DEBUG;
                    }

                    @Override
                    public void setLogLevel(LogLevel logLevel) {

                    }

                    @Override
                    public ConsoleOutput getConsoleOutput() {
                        return ConsoleOutput.Rich;
                    }

                    @Override
                    public void setConsoleOutput(ConsoleOutput consoleOutput) {

                    }

                    @Override
                    public WarningMode getWarningMode() {
                        return WarningMode.All;
                    }

                    @Override
                    public void setWarningMode(WarningMode warningMode) {

                    }

                    @Override
                    public ShowStacktrace getShowStacktrace() {
                        return ShowStacktrace.ALWAYS_FULL;
                    }

                    @Override
                    public void setShowStacktrace(ShowStacktrace showStacktrace) {

                    }
                }, (output, args) -> {

                });
        buildExceptionReporter.buildFinished(new BuildResult(
                project.getGradle(),
                new MultipleBuildOperationFailures(throwables, null)
        ));
    }
}
