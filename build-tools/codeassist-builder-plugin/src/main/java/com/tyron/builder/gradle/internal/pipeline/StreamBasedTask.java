package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.Nullable;
import com.google.common.base.Preconditions;
import com.tyron.builder.gradle.internal.tasks.AndroidVariantTask;
import com.google.common.collect.Iterables;
import java.util.Collection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.work.DisableCachingByDefault;

/**
 * A base task with stream fields that properly use Gradle's input/output annotations to return the
 * stream's content as input/output.
 */
@DisableCachingByDefault
//@BuildAnalyzer(primaryTaskCategory = TaskCategory.COMPILED_CLASSES, secondaryTaskCategories = {TaskCategory.SOURCE_PROCESSING})
public abstract class StreamBasedTask extends AndroidVariantTask {

    /** Registered as task input in {@link #registerConsumedAndReferencedStreamInputs()}. */
    protected Collection<TransformStream> consumedInputStreams;
    /** Registered as task input in {@link #registerConsumedAndReferencedStreamInputs()}. */
    protected Collection<TransformStream> referencedInputStreams;

    protected IntermediateStream outputStream;

    @Nullable
    @Optional
    @OutputDirectory
    public abstract DirectoryProperty getStreamOutputFolder();

    /**
     * We register each of the streams as a separate input in order to get incremental updates per
     * stream. Relative path sensitivity is used in the context of a single stream, and all
     * incremental input updates will be provided per stream.
     *
     * <p>DO NOT change this to a method annotated with {@link org.gradle.api.tasks.InputFiles} that
     * returns {@link Iterables<org.gradle.api.file.FileTree>} which would consider all streams as a
     * single input. In that case, file change is processed relative to all file tree roots.
     * Removing a file {@code test/A.class} from stream X, and adding a new {@code test/A.class} to
     * stream Y would yield only a single CHANGE update for file {@code test/A.class}, although we
     * would expect to get DELETED and ADDED file incremental update.
     */
    protected void registerConsumedAndReferencedStreamInputs() {
        Preconditions.checkNotNull(consumedInputStreams, "Consumed input streams not set.");
        Preconditions.checkNotNull(referencedInputStreams, "Referenced input streams not set.");
        for (TransformStream stream :
                Iterables.concat(consumedInputStreams, referencedInputStreams)) {
            // This cannot be PathSensitivity.RELATIVE, as transforms currently decide where to
            // place outputs based on input names, which are lost by this input, but full file path
            // is not a terrible approximation for this.
            // See https://issuetracker.google.com/68144982
            getInputs().files(stream.getAsFileTree()).withPathSensitivity(PathSensitivity.ABSOLUTE);
        }
    }
}
