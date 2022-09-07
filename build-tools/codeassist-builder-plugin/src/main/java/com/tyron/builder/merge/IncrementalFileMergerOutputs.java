package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Factories for instances of {@link IncrementalFileMergerOutput}.
 */
public final class IncrementalFileMergerOutputs {

    /**
     * Utility class: no constructor.
     */
    private IncrementalFileMergerOutputs() {}

    /**
     * Creates a new output that merges files using the provided algorithm and writes the merged
     * file using the provided writer. This output decouples the actual file-merging algorithm (how
     * to merge files) from file writing.
     *
     * <p>While the general definition of {@link IncrementalFileMergerOutput}
     * states that the merge will result in a file being written to output coming from a list of
     * inputs, in practice, the step is two-process: first the file is merged (the {@code f()}
     * function as described in the package documentation is applied) and then it is written to
     * an output tree.
     *
     * <p>This factory method creates an output that first decides what data needs to be written
     * by using a {@link StreamMergeAlgorithm} and then performs the actual writing using
     * {@link MergeOutputWriter}. See {@link StreamMergeAlgorithms} for a list of algorithms and
     * {@link MergeOutputWriters} for a list of writers.
     *
     * @param algorithm the algorithm to merge files (not used for files that are removed)
     * @param writer the writer that builds the output
     * @return the output
     */
    @NonNull
    public static IncrementalFileMergerOutput fromAlgorithmAndWriter(
            @NonNull StreamMergeAlgorithm algorithm,
            @NonNull MergeOutputWriter writer) {
        return new IncrementalFileMergerOutput() {

            @Override
            public void open() {
                writer.open();
            }

            @Override
            public void close() throws IOException {
                writer.close();
            }

            @Override
            public void remove(@NonNull String path) {
                writer.remove(path);
            }

            @Override
            public void create(
                    @NonNull String path,
                    @NonNull List<IncrementalFileMergerInput> inputs,
                    boolean compress) {
                try (Closer closer = Closer.create()) {
                    List<InputStream> inStreams =
                            inputs.stream().map(i -> i.openPath(path)).collect(Collectors.toList());
                    InputStream mergedStream =
                            algorithm.merge(path, ImmutableList.copyOf(inStreams), closer);
                    writer.create(path, mergedStream, compress);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (DuplicateRelativeFileException e) {
                    throw new DuplicateRelativeFileException(inputs, e);
                }
            }

            @Override
            public void update(
                    @NonNull String path,
                    @NonNull List<String> prevInputNames,
                    @NonNull List<IncrementalFileMergerInput> inputs,
                    boolean compress) {
                try (Closer closer = Closer.create()) {
                    List<InputStream> inStreams =
                            inputs.stream().map(i -> i.openPath(path)).collect(Collectors.toList());
                    InputStream mergedStream =
                            algorithm.merge(path, ImmutableList.copyOf(inStreams), closer);
                    writer.replace(path, mergedStream, compress);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        };
    }
}
