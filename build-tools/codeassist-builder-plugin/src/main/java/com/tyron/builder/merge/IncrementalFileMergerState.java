package com.tyron.builder.merge;

import com.android.annotations.NonNull;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State of an incremental file merge. State is used to record the state of a merge so that an
 * incremental merge can be performed afterwards. An initial state (for a full merge) can be
 * build using {@link #IncrementalFileMergerState()}.
 *
 * <p>States are serializable so they can be persisted across invocations of merge operations. They
 * are also immutable. The incremental merger will build new instances using {@link Builder}.
 *
 * <p>Users of the incremental merger will generally not need to use anything from the state,
 * except providing it to invocations of
 * {@link IncrementalFileMerger#merge(List, IncrementalFileMergerOutput,
 * IncrementalFileMergerState)}. Therefore, this class is mostly opaque.
 */
public final class IncrementalFileMergerState implements Serializable {

    /**
     * Version for serialization.
     */
    private static final long serialVersionUID = 1;

    /**
     * Names of all inputs to merge, in order.
     */
    @NonNull
    private final ImmutableList<String> inputNames;

    /**
     * Maps OS-independent paths to the names of the input sets that were used to construct the
     * merged output.
     */
    @NonNull
    private final ImmutableMap<String, ImmutableList<String>> origin;

    /**
     * Maps an input set name to all OS-independent paths to whom it contributed inputs for. This
     * map can be build from {@link #origin}: its key are all different values of all lists in the
     * value set of {@link #origin}. For each key in {@link #byInput}, its values are all keys
     * in {@link #origin} whose value contains the key.
     *
     * <p>However, for performance reasons, this is precomputed.
     *
     * <p>For example, if we have a structure of:
     * <pre>
     * input1:
     *   - path1
     *   - path2
     *
     * input2:
     *   - path1
     *   - path3
     * </pre>
     *
     * <p>{@link #origin} would contain:
     * <pre>
     * path1 -> input1, input2
     * path2 -> input1
     * path3 -> input2
     * </pre>
     *
     * <p>{@link #byInput} would contains:
     * <pre>
     * input1 -> path1, path2
     * input2 -> path1, path3
     * </pre>
     */
    @NonNull
    private final ImmutableMap<String, ImmutableSet<String>> byInput;

    /**
     * Creates a new, empty, state. This is useful to create a full build as a full build is an
     * incremental build from zero.
     */
    public IncrementalFileMergerState() {
        inputNames = ImmutableList.of();
        origin = ImmutableMap.of();
        byInput = ImmutableMap.of();
    }

    /**
     * Creates a new state copying provided data. This is invoked from the {@link Builder}.
     *
     * @param inputNames the names of the inputs for the merge
     * @param origin maps OS-independent paths to the names of the inputs that contributed to the
     *     merged output path
     * @param byInput maps input names to all OS-independent paths that it contains
     */
    IncrementalFileMergerState(
            @NonNull List<String> inputNames,
            @NonNull Map<String, List<String>> origin,
            @NonNull Map<String, Set<String>> byInput) {

        ImmutableMap.Builder<String, ImmutableList<String>> originBuilder = ImmutableMap.builder();
        for (Map.Entry<String, List<String>> e : origin.entrySet()) {
            originBuilder.put(e.getKey(), ImmutableList.copyOf(e.getValue()));
        }

        ImmutableMap.Builder<String, ImmutableSet<String>> byInputBuilder = ImmutableMap.builder();
        for (Map.Entry<String, Set<String>> e : byInput.entrySet()) {
            byInputBuilder.put(e.getKey(), ImmutableSet.copyOf(e.getValue()));
        }

        this.inputNames = ImmutableList.copyOf(inputNames);
        this.origin = originBuilder.build();
        this.byInput = byInputBuilder.build();
    }

    /**
     * Obtains the names of inputs.
     *
     * @return the names of all inputs
     */
    @NonNull
    ImmutableList<String> getInputNames() {
        return inputNames;
    }

    /**
     * Obtains the names of the inputs that contributed to an output provided by an OS-independent
     * path.
     *
     * @param path the path
     * @return the list of names of inputs, an empty list if the path does not exit
     */
    @NonNull
    ImmutableList<String> inputsFor(@NonNull String path) {
        ImmutableList<String> names = origin.get(path);
        if (names == null) {
            return ImmutableList.of();
        } else {
            return names;
        }
    }

    /**
     * Obtains the set of all OS-independent paths that correspond to an input.
     *
     * @param name the input's name
     * @return all files, an empty set if the input is not known
     */
    @NonNull
    ImmutableSet<String> filesOf(@NonNull String name) {
        ImmutableSet<String> files = byInput.get(name);
        if (files == null) {
            return ImmutableSet.of();
        } else {
            return files;
        }
    }

    /**
     * Builder used to create a {@link IncrementalFileMergerState}.
     */
    static class Builder {

        /**
         * Mutable version of {@link IncrementalFileMergerState#inputNames}.
         */
        @NonNull
        private List<String> inputNames;

        /** Mutable version of {@link IncrementalFileMergerState#origin}. */
        @NonNull private Map<String, List<String>> origin;

        /**
         * Mutable version of {@link IncrementalFileMergerState#byInput}.
         */
        @NonNull
        private Map<String, Set<String>> byInput;

        /**
         * Creates a new builder, cloning an existing state as a starting point.
         *
         * @param state the state to clone
         */
        Builder(@NonNull IncrementalFileMergerState state) {
            inputNames = new ArrayList<>(state.inputNames);

            origin = new HashMap<>();
            for (Map.Entry<String, ImmutableList<String>> e : state.origin.entrySet()) {
                origin.put(e.getKey(), new ArrayList<>(e.getValue()));
            }

            byInput = new HashMap<>();
            for (Map.Entry<String, ImmutableSet<String>> e : state.byInput.entrySet()) {
                byInput.put(e.getKey(), new HashSet<>(e.getValue()));
            }
        }

        /**
         * Sets what the list of input names for the state are. This will drop any paths currently
         * in the builder for any inputs not in this list.
         *
         * @param inputNames the list of input names
         */
        void setInputNames(@NonNull List<String> inputNames) {
            this.inputNames = new ArrayList<>(inputNames);

            /*
             * Search for removed input names and remove all known files associated with them.
             */
            for (Iterator<String> it = byInput.keySet().iterator(); it.hasNext(); ) {
                String in = it.next();
                if (!inputNames.contains(in)) {
                    Set<String> paths = byInput.get(in);
                    for (String p : paths) {
                        List<String> inputs = origin.get(p);
                        int idx = inputs.indexOf(in);
                        assert idx >= 0;
                        if (inputs.size() == 1) {
                            origin.remove(p);
                        } else {
                            inputs.remove(idx);
                        }
                    }

                    it.remove();
                }
            }
        }

        /**
         * Removes an OS-independent path from the state.
         *
         * @param path the path to remove; it may not exist in the state
         */
        void remove(@NonNull String path) {
            List<String> names = origin.get(path);
            if (names == null) {
                return;
            }

            for (String n : names) {
                Set<String> files = byInput.get(n);
                assert files != null;
                files.remove(path);
            }

            origin.remove(path);
        }

        /**
         * Sets the input sets that were used to build a path.
         *
         * @param path the OS-independent path
         * @param names the names of the inputs used to build the output
         */
        void set(@NonNull String path, @NonNull List<String> names) {
            remove(path);

            assert inputNames.containsAll(names);

            origin.put(path, new ArrayList<>(names));
            for (String n : names) {
                Set<String> files = byInput.computeIfAbsent(n, k -> new HashSet<>());
                files.add(path);
            }
        }

        /**
         * Creates a new {@link IncrementalFileMergerState} from the current state.
         *
         * @return the new state
         */
        @NonNull
        IncrementalFileMergerState build() {
            return new IncrementalFileMergerState(inputNames, origin, byInput);
        }
    }
}
