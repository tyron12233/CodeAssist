package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.api.transform.Context;
import com.tyron.builder.api.transform.QualifiedContent;
import com.tyron.builder.api.transform.SecondaryFile;
import com.tyron.builder.api.transform.SecondaryInput;
import com.tyron.builder.api.transform.Transform;
import com.tyron.builder.api.transform.TransformException;
import com.tyron.builder.api.transform.TransformInput;
import com.tyron.builder.gradle.internal.services.BuildServicesKt;
import com.tyron.builder.gradle.internal.tasks.factory.TaskCreationAction;
import com.google.common.collect.ImmutableMap;
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.logging.LoggingManager;
import org.gradle.api.specs.Spec;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectories;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.OutputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.workers.WorkerExecutor;

/** A task running a transform. */
@CacheableTask
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.COMPILED_CLASSES, secondaryTaskCategories = {TaskCategory.SOURCE_PROCESSING})
public abstract class TransformTask extends StreamBasedTask {

    private Transform transform;
    Collection<SecondaryFile> secondaryFiles = null;
    List<FileCollection> secondaryInputFiles = null;

    @Internal
    public Transform getTransform() {
        return transform;
    }

    @Input
    @NonNull
    public Set<? super QualifiedContent.Scope> getScopes() {
        return transform.getScopes();
    }

    @Input
    @NonNull
    public Set<? super QualifiedContent.Scope> getReferencedScopes() {
        return transform.getReferencedScopes();
    }

    @Input
    @NonNull
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return transform.getInputTypes();
    }

    @InputFiles
    // Use ABSOLUTE to be safe, the API that this method calls is deprecated anyway.
    @PathSensitive(PathSensitivity.ABSOLUTE)
    @Optional
    public Collection<File> getOldSecondaryInputs() {
        //noinspection deprecation: Needed for backward compatibility.
        return transform.getSecondaryFileInputs();
    }

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public List<FileCollection> getSecondaryFileInputs() {
        return secondaryInputFiles;
    }

    @OutputFiles
    public Map<String, File> getOtherFileOutputs() {

        ImmutableMap.Builder<String, File> builder = new ImmutableMap.Builder<>();
        int index = 0;
        for (File outputFile : transform.getSecondaryFileOutputs()) {
            builder.put("otherFileOutput" + Integer.toString(++index), outputFile);
        }

        return builder.build();
    }

    @OutputDirectory
    @Optional
    @NonNull
    public abstract DirectoryProperty getOutputDirectory();

    @OutputFile
    @Optional
    @NonNull
    public abstract RegularFileProperty getOutputFile();

    @OutputDirectories
    public Map<String, File> getOtherFolderOutputs() {
        ImmutableMap.Builder<String, File> builder = new ImmutableMap.Builder<>();
        int index = 0;
        for (File outputFolder : transform.getSecondaryDirectoryOutputs()) {
            builder.put("otherFolderOutput" + Integer.toString(++index), outputFolder);
        }

        return builder.build();
    }

    @Input
    public Map<String, Object> getOtherInputs() {
        return transform.getParameterInputs();
    }

    /**
     * Returns a list of non incremental TransformInput.
     *
     * @param streams the streams.
     * @return a list of non-incremental TransformInput matching the content of the streams.
     */
    @NonNull
    protected static List<TransformInput> computeNonIncTransformInput(
            @NonNull Collection<TransformStream> streams) {
        return streams.stream()
                .map(TransformStream::asNonIncrementalInput)
                .collect(Collectors.toList());
    }

    protected void runTransform(
            List<TransformInput> consumedInputs,
            List<TransformInput> referencedInputs,
            boolean isIncremental,
            Collection<SecondaryInput> changedSecondaryInputs
    ) throws IOException, TransformException, InterruptedException {
        Context context =
                new Context() {
                    @Override
                    public LoggingManager getLogging() {
                        return TransformTask.this.getLogging();
                    }

                    @Override
                    public File getTemporaryDir() {
                        return TransformTask.this.getTemporaryDir();
                    }

                    @Override
                    public String getPath() {
                        return TransformTask.this.getPath();
                    }

                    @Override
                    public String getProjectName() {
                        return TransformTask.this.getProjectPath().get();
                    }

                    @NonNull
                    @Override
                    public String getVariantName() {
                        return TransformTask.this.getVariantName();
                    }

                    @NonNull
                    @Override
                    public WorkerExecutor getWorkerExecutor() {
                        return TransformTask.this.getWorkerExecutor();
                    }
                };
        getTransform()
                .transform(
                        new TransformInvocationBuilder(context)
                                .addInputs(consumedInputs)
                                .addReferencedInputs(referencedInputs)
                                .addSecondaryInputs(changedSecondaryInputs)
                                .addOutputProvider(
                                        outputStream != null
                                                ? outputStream.asOutput()
                                                : null)
                                .setIncrementalMode(isIncremental)
                                .build());

        if (outputStream != null) {
            outputStream.save();
        }
    }

//    protected void runTransform(
//            List<TransformInput> consumedInputs,
//            List<TransformInput> referencedInputs,
//            boolean isIncremental,
//            Collection<SecondaryInput> changedSecondaryInputs,
//            GradleTransformExecution preExecutionInfo,
//            AnalyticsService analyticsService) {
//        GradleTransformExecution executionInfo =
//                preExecutionInfo.toBuilder().setIsIncremental(isIncremental).build();
//
//        analyticsService.recordBlock(
//                GradleBuildProfileSpan.ExecutionType.TASK_TRANSFORM,
//                executionInfo,
//                getProjectPath().get(),
//                getVariantName(),
//                new Recorder.VoidBlock() {
//                    @Override
//                    public void call() throws Exception {
//
//                    }
//                });
//    }

    public static class CreationAction<T extends Transform>
            extends TaskCreationAction<TransformTask> {

        @NonNull
        private final String variantName;
        @NonNull
        private final String taskName;
        @NonNull
        private final T transform;
        @NonNull
        private Collection<TransformStream> consumedInputStreams;
        @NonNull
        private Collection<TransformStream> referencedInputStreams;
        @Nullable
        private IntermediateStream outputStream;
        private boolean allowIncremental;

        CreationAction(
                @NonNull String variantName,
                @NonNull String taskName,
                @NonNull T transform,
                @NonNull Collection<TransformStream> consumedInputStreams,
                @NonNull Collection<TransformStream> referencedInputStreams,
                @Nullable IntermediateStream outputStream,
                boolean allowIncremental) {
            this.variantName = variantName;
            this.taskName = taskName;
            this.transform = transform;
            this.consumedInputStreams = consumedInputStreams;
            this.referencedInputStreams = referencedInputStreams;
            this.outputStream = outputStream;
            this.allowIncremental = allowIncremental;
        }

        @NonNull
        @Override
        public String getName() {
            return taskName;
        }

        @SuppressWarnings({
            "unchecked",
            "rawtypes"
        }) // Task has been subtyped for now, but will be going away soon
        @NonNull
        @Override
        public Class<TransformTask> getType() {
            if (allowIncremental) {
                return (Class<TransformTask>) (Class) IncrementalTransformTask.class;
            } else {
                return (Class<TransformTask>) (Class) NonIncrementalTransformTask.class;
            }
        }

        @Override
        public void configure(@NonNull TransformTask task) {
            task.transform = transform;
            transform.setOutputDirectory(task.getOutputDirectory());
            transform.setOutputFile(task.getOutputFile());
            task.consumedInputStreams = consumedInputStreams;
            task.referencedInputStreams = referencedInputStreams;
            task.outputStream = outputStream;
            if (outputStream != null) {
                task.getStreamOutputFolder()
                        .fileProvider(task.getProject().provider(outputStream::getRootLocation));
            }
            task.setVariantName(variantName);
            boolean cachingEnabled = transform.isCacheable();
            task.getOutputs()
                    .cacheIf(
                            "Transform "
                                    + transform.getClass().getName()
                                    + " declares itself as cacheable",
                            (Spec<? super Task> & Serializable) (t -> cachingEnabled));
            task.registerConsumedAndReferencedStreamInputs();
            task.getProjectPath().set(task.getProject().getPath());
            task.secondaryInputFiles =
                    transform.getSecondaryFiles().stream()
                            .map(
                                    secondaryFile ->
                                            secondaryFile.getFileCollection(task.getProject()))
                            .collect(Collectors.toList());
//            task.getAnalyticsService().set(BuildServicesKt.getBuildService(
//                    task.getProject().getGradle().getSharedServices(), AnalyticsService.class));
        }
    }
}
