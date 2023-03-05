package com.tyron.builder.gradle.internal.pipeline;

import com.android.annotations.NonNull;
import com.google.common.collect.ImmutableList;
import java.util.Collection;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;

/**
 * A collection of {@link TransformStream} that can be queried.
 */
public abstract class FilterableStreamCollection {

    @NonNull
    abstract Project getProject();

    @NonNull
    abstract Collection<TransformStream> getStreams();

    @NonNull
    public ImmutableList<TransformStream> getStreams(@NonNull StreamFilter streamFilter) {
        ImmutableList.Builder<TransformStream> streamsByType = ImmutableList.builder();
        for (TransformStream s : getStreams()) {
            if (streamFilter.accept(s.getContentTypes(), s.getScopes())) {
                streamsByType.add(s);
            }
        }

        return streamsByType.build();
    }

    /**
     * Returns a single collection that contains all the files and task dependencies from the
     * streams matching the {@link StreamFilter}.
     * @param streamFilter the stream filter.
     * @return a collection.
     */
    @NonNull
    public FileCollection getPipelineOutputAsFileCollection(
            @NonNull StreamFilter streamFilter) {
        return getPipelineOutputAsFileCollection(streamFilter, streamFilter);
    }

    @NonNull
    public FileCollection getPipelineOutputAsFileCollection(
            @NonNull StreamFilter streamFilter, @NonNull StreamFilter contentFilter) {
        final Project project = getProject();

        ImmutableList<TransformStream> streams = getStreams(streamFilter);
        if (streams.isEmpty()) {
            return project.files();
        }

        if (streams.size() == 1) {
            return streams.get(0).getOutputFileCollection(project, contentFilter);
        }

        // create a global collection that will return all the collections.
        ConfigurableFileCollection collection = project.files();

        for (TransformStream stream : streams) {
            collection.from(stream.getOutputFileCollection(project, contentFilter));
        }

        return collection;
    }
}
