package com.tyron.builder.api.internal.tasks.execution;

import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.api.internal.TaskInternal;
import com.tyron.builder.internal.execution.BuildOutputCleanupRegistry;
import com.tyron.builder.internal.execution.OutputChangeListener;
import com.tyron.builder.internal.execution.history.OutputFilesRepository;
import com.tyron.builder.internal.file.Deleter;
import com.tyron.builder.internal.operations.BuildOperationContext;
import com.tyron.builder.internal.operations.BuildOperationDescriptor;
import com.tyron.builder.internal.operations.BuildOperationExecutor;
import com.tyron.builder.internal.operations.RunnableBuildOperation;
import com.tyron.builder.api.internal.tasks.TaskExecuter;
import com.tyron.builder.api.internal.tasks.TaskExecuterResult;
import com.tyron.builder.api.internal.tasks.TaskExecutionContext;
import com.tyron.builder.api.internal.tasks.TaskStateInternal;
import com.tyron.builder.api.internal.tasks.properties.FilePropertySpec;
import com.tyron.builder.api.internal.tasks.properties.TaskProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class CleanupStaleOutputsExecuter implements TaskExecuter {

    private static final String CLEAN_STALE_OUTPUTS_DISPLAY_NAME = "Clean stale outputs";

    private final Logger logger = LoggerFactory.getLogger(CleanupStaleOutputsExecuter.class);
    private final BuildOperationExecutor buildOperationExecutor;
    private final BuildOutputCleanupRegistry cleanupRegistry;
    private final Deleter deleter;
    private final OutputChangeListener outputChangeListener;
    private final OutputFilesRepository outputFilesRepository;
    private final TaskExecuter executer;

    public CleanupStaleOutputsExecuter(
            BuildOperationExecutor buildOperationExecutor,
            BuildOutputCleanupRegistry cleanupRegistry,
            Deleter deleter,
            OutputChangeListener outputChangeListener,
            OutputFilesRepository outputFilesRepository,
            TaskExecuter executer
    ) {
        this.buildOperationExecutor = buildOperationExecutor;
        this.cleanupRegistry = cleanupRegistry;
        this.deleter = deleter;
        this.outputChangeListener = outputChangeListener;
        this.outputFilesRepository = outputFilesRepository;
        this.executer = executer;
    }

    @Override
    public TaskExecuterResult execute(TaskInternal task,
                                      TaskStateInternal state,
                                      TaskExecutionContext context) {
        if (!task.getReasonNotToTrackState().isPresent()) {
            cleanupStaleOutputs(context);
        }
        return executer.execute(task, state, context);
    }

    private void cleanupStaleOutputs(TaskExecutionContext context) {
        Set<File> filesToDlete = new HashSet<>();
        TaskProperties properties = context.getTaskProperties();
        for (FilePropertySpec outputFilesSpec : properties.getOutputFileProperties()) {
            FileCollection files = outputFilesSpec.getPropertyFiles();
            for (File file : files) {
                if (cleanupRegistry.isOutputOwnedByBuild(file) && !outputFilesRepository.isGeneratedByGradle(file) && file.exists()) {
                    filesToDlete.add(file);
                }
            }
        }
        if (!filesToDlete.isEmpty()) {
            outputChangeListener.beforeOutputChange(
                    filesToDlete.stream()
                    .map(File::getAbsolutePath)
                    .collect(Collectors.toList())
            );
            buildOperationExecutor.run(new RunnableBuildOperation() {
                @Override
                public void run(BuildOperationContext context) throws Exception {
                    for (File file : filesToDlete) {
                        if (file.exists()) {
                            logger.info("Deleting stale output file: {}", file.getAbsolutePath());
                            deleter.deleteRecursively(file);
                        }
                    }
                }

                @Override
                public BuildOperationDescriptor.Builder description() {
                    return BuildOperationDescriptor
                            .displayName(CLEAN_STALE_OUTPUTS_DISPLAY_NAME)
                            .progressDisplayName("Cleaning stale outputs");
                }
            });
        }
    }
}
