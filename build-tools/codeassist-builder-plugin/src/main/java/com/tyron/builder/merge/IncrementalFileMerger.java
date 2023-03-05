package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.android.ide.common.resources.FileStatus;
import com.google.common.collect.ImmutableList;
import com.google.common.io.Closer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Class performing an incremental merge operation. This class is a utility with only one method,
 * {@link #merge(List, IncrementalFileMergerOutput, IncrementalFileMergerState, Predicate)}. See
 * package description for a discussion on merging concepts.
 */
public final class IncrementalFileMerger {

    /**
     * No constructor.
     */
    private IncrementalFileMerger() {}

    /**
     * Performs an incremental merge operation. An incremental merge operation is done by applying a
     * set of changes represented by a list of instances of {@link IncrementalFileMergerInput} to a
     * current state represented by an instance of {@link IncrementalFileMergerState}. All changes
     * that need to be made to the output as a consequence of the inputs are reported to {@code
     * output}.
     *
     * <p>See package description for details.
     *
     * @param inputs all inputs representing changes to the inputs
     * @param output object receiving information about all changes that need to be made to the
     *     output
     * @param state state of the previous merge; an empty state if this is the first merge
     * @param noCompressPredicate a predicate indicating whether paths should be uncompressed
     */
    @SuppressWarnings("UnstableApiUsage")
    @NonNull
    public static IncrementalFileMergerState merge(
            @NonNull List<IncrementalFileMergerInput> inputs,
            @NonNull IncrementalFileMergerOutput output,
            @NonNull IncrementalFileMergerState state,
            @NonNull Predicate<String> noCompressPredicate) {

        IncrementalFileMergerState.Builder newState = new IncrementalFileMergerState.Builder(state);
        newState.setInputNames(getInputNames(inputs));

        /*
         * Figure out if there changes in the inputs, either new inputs, remove inputs or inputs
         * changing order. Use a different algorithm in each case.
         */
        List<String> inputNames =
                inputs.stream()
                        .map(IncrementalFileMergerInput::getName)
                        .collect(Collectors.toList());

        try (Closer closer = Closer.create()) {
            inputs.forEach(
                    it -> {
                        it.open();
                        closer.register(it);
                    });
            output.open();
            closer.register(output);
            if (inputNames.equals(state.getInputNames())) {
                mergeNoChangedInputs(inputs, output, state, newState, noCompressPredicate);
            } else {
                mergeChangedInputs(inputs, output, state, newState, noCompressPredicate);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return newState.build();
    }

    /**
     * Merges the changes when the inputs themselves have not changed, only individual files.
     *
     * @param inputs all inputs representing changes to the inputs
     * @param output object receiving information about all changes that need to be made to the
     *     output
     * @param state state of the previous merge; an empty state if this is the first merge
     * @param newState the new state of the merge; has the new inputs already set
     * @param noCompressPredicate a predicate indicating whether paths should be uncompressed
     */
    private static void mergeNoChangedInputs(
            @NonNull List<IncrementalFileMergerInput> inputs,
            @NonNull IncrementalFileMergerOutput output,
            @NonNull IncrementalFileMergerState state,
            @NonNull IncrementalFileMergerState.Builder newState,
            @NonNull Predicate<String> noCompressPredicate) {

        /*
         * Make the set of all impacted paths.
         */
        Set<String> impactedPaths = new HashSet<>();
        inputs.forEach(i -> impactedPaths.addAll(i.getUpdatedPaths()));

        /*
         * For each impacted path, update the output and the new state.
         */
        for (String path : impactedPaths) {
            ImmutableList<String> prevInputNames = state.inputsFor(path);
            List<IncrementalFileMergerInput> inputsForFile = getInputsForFile(path, inputs, state);

            updateChangedFile(
                    inputsForFile,
                    prevInputNames,
                    output,
                    path,
                    newState,
                    !noCompressPredicate.test(path));
        }
    }

    /**
     * Merges the changes when the inputs themselves have changed. This means that input sets may
     * have been added, removed or changed order.
     *
     * @param inputs all inputs representing changes to the inputs
     * @param output object receiving information about all changes that need to be made to the
     *     output
     * @param state state of the previous merge; an empty state if this is the first merge
     * @param newState the new state of the merge; has the new inputs already set
     * @param noCompressPredicate a predicate indicating whether paths should be uncompressed
     */
    private static void mergeChangedInputs(
            @NonNull List<IncrementalFileMergerInput> inputs,
            @NonNull IncrementalFileMergerOutput output,
            @NonNull IncrementalFileMergerState state,
            @NonNull IncrementalFileMergerState.Builder newState,
            @NonNull Predicate<String> noCompressPredicate) {
        /*
         * We temporarily extend the new state with all input names, including the ones that were
         * in the state and the new ones. At the end we will remove all names that are not in
         * use any more.
         */
        List<String> allNames = new ArrayList<>(state.getInputNames());
        List<String> newInputNameList =
                inputs.stream()
                        .map(IncrementalFileMergerInput::getName)
                        .peek(n -> {
                            if (!allNames.contains(n)) {
                                allNames.add(n);
                            }
                        })
                        .collect(Collectors.toList());
        newState.setInputNames(allNames);

        /*
         * We need to compute the impact set, the set of all files that have changed. However,
         * because input sets may have changed order, or been removed, we start by collecting a
         * superset of the impact set: the set of all files.
         *
         * These files are all known files (in the previous state), plus all files changed by any
         * of the input. Note that we don't need the files known by the inputs since either they
         * are new (in case they appear in the updated set) or they are referred to in the previous
         * state.
         */
        Set<String> maybeImpactedPaths = new HashSet<>();
        state.getInputNames().forEach(n -> maybeImpactedPaths.addAll(state.filesOf(n)));
        inputs.forEach(i -> maybeImpactedPaths.addAll(i.getUpdatedPaths()));

        /*
         * For each path that may have been impacted, compute the old and new list of inputs.
         * If the lists differ, then leave the paths in the set. If the lists are the same, check
         * if any of the input sets has changed the path. If none has changed the path, then don't
         * merge. If anything has changed, redo the merge.
         */
        for (String path : maybeImpactedPaths) {
            ImmutableList<String> prevInputNames = state.inputsFor(path);
            List<IncrementalFileMergerInput> inputsForFile = getInputsForFile(path, inputs, state);

            boolean changed = false;
            if (prevInputNames.size() != inputsForFile.size()) {
                changed = true;
            }

            for (int i = 0; !changed && i < prevInputNames.size(); i++) {
                if (!prevInputNames.get(i).equals(inputsForFile.get(i).getName())) {
                    changed = true;
                }
            }

            if (!changed && inputsForFile.stream().anyMatch(i -> i.getFileStatus(path) != null)) {
                changed = true;
            }

            if (changed) {
                updateChangedFile(
                        inputsForFile,
                        prevInputNames,
                        output,
                        path,
                        newState,
                        !noCompressPredicate.test(path));
            }
        }

        /*
         * Set the list of input names to the final list.
         */
        newState.setInputNames(newInputNameList);
    }

    /**
     * Updates a file that has changed. Will update the output and the new state.
     *
     * @param inputsForFile current inputs for the file
     * @param prevInputNames names of previous inputs for the file
     * @param output output to write to
     * @param path file path
     * @param newState new merger state to update
     * @param compress whether the data will be compressed
     */
    private static void updateChangedFile(
            @NonNull List<IncrementalFileMergerInput> inputsForFile,
            @NonNull ImmutableList<String> prevInputNames,
            @NonNull IncrementalFileMergerOutput output,
            @NonNull String path,
            @NonNull IncrementalFileMergerState.Builder newState,
            boolean compress) {
        if (inputsForFile.isEmpty()) {
            // No current inputs have this file, remove it.
            output.remove(path);
            newState.remove(path);
        } else if (prevInputNames.isEmpty()) {
            // No old inputs had the file, create a new one.
            output.create(path, ImmutableList.copyOf(inputsForFile), compress);
            newState.set(path, getInputNames(inputsForFile));
        } else {
            // Both new and old inputs have the file, update it.
            output.update(path, prevInputNames, ImmutableList.copyOf(inputsForFile), compress);
            newState.set(path, getInputNames(inputsForFile));
        }
    }

    /**
     * Determines what are the inputs that should contribute with files for a merged path.
     *
     * @param path the path
     * @param inputs all inputs for the merge
     * @param state the old merge state
     * @return the inputs that contribute to the path; an empty list if the merged file should
     * be removed
     */
    private static List<IncrementalFileMergerInput> getInputsForFile(
            @NonNull String path,
            @NonNull List<IncrementalFileMergerInput> inputs,
            @NonNull IncrementalFileMergerState state) {
        return inputs.stream()
                .filter(
                        i -> {
                            FileStatus status = i.getFileStatus(path);
                            if (status != null) {
                                return status != FileStatus.REMOVED;
                            } else {
                                // status == null indicates that either (1) i doesn't contain the
                                // given path or (2) the path's contents are unchanged.
                                // We only return true if the input actually contains the given
                                // path, but calling i.getAllPaths() is expensive, so we do some
                                // other checks before calling it.
                                if (state.getInputNames().isEmpty()) {
                                    // if state.getInputNames().isEmpty() we know i doesn't contain
                                    // the path, otherwise the status would be NEW.
                                    return false;
                                } else if (state.getInputNames().contains(i.getName())) {
                                    return state.inputsFor(path).contains(i.getName());
                                } else {
                                    return i.getAllPaths().contains(path);
                                }
                            }
                        })
                .collect(Collectors.toList());
    }

    /**
     * Transforms a list of {@link IncrementalFileMergerInput} into a list with the input names.
     *
     * @param inputs the inputs
     * @return a list with the input names
     */
    @NonNull
    private static ImmutableList<String> getInputNames(
            @NonNull List<IncrementalFileMergerInput> inputs) {
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        inputs.forEach(i -> builder.add(i.getName()));
        return builder.build();
    }
}
