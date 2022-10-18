package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import java.util.List;
import java.util.function.Predicate;

/**
 * Output of a merge operation. The output receives notifications of the operations that need to be
 * performed to execute the merge.
 *
 * <p>Operations on the output should only be done once the inputs have been open
 * (see {@link IncrementalFileMergerInput#open()}.
 *
 * <p>Outputs need to be open before any operations can be performed and need to be closed to
 * ensure all changes have been persisted.
 *
 * <p>In general, an output obtained from {@link IncrementalFileMergerOutputs} is used.
 */
public interface IncrementalFileMergerOutput extends OpenableCloseable {

    /**
     * A path needs to be removed from the output.
     *
     * @param path the OS-independent path to remove
     */
    void remove(@NonNull String path);

    /**
     * A path needs to be created.
     *
     * @param path the OS-independent path
     * @param inputs the inputs where the paths exists and that should be combined to generate the
     *     output. The inputs should already be opened when passed to this method. The inputs are
     *     provided in the same order they were provided to {@link IncrementalFileMerger#merge(List,
     *     IncrementalFileMergerOutput, IncrementalFileMergerState, Predicate)}
     * @param compress whether the data will be compressed
     */
    void create(
            @NonNull String path,
            @NonNull List<IncrementalFileMergerInput> inputs,
            boolean compress);

    /**
     * A path needs to be updated.
     *
     * @param path the OS-independent path
     * @param prevInputNames the previous inputs used to create or update the path
     * @param inputs the inputs where the paths exists and that should be combined to generate the
     *     output. The inputs should already be opened when passed to this method. The inputs are
     *     provided in the same order they were provided to {@link IncrementalFileMerger#merge(List,
     *     IncrementalFileMergerOutput, IncrementalFileMergerState, Predicate)}
     * @param compress whether the data will be compressed
     */
    void update(
            @NonNull String path,
            @NonNull List<String> prevInputNames,
            @NonNull List<IncrementalFileMergerInput> inputs,
            boolean compress);
}
