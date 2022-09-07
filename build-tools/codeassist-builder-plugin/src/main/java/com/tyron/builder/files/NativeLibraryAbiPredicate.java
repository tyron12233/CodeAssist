package com.tyron.builder.files;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.google.common.collect.Sets;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Predicate applicable to file paths that only accepts native libraries optionally filtered by a
 * set of ABIs.
 *
 * <p>This predicate will also accept the {@link SdkConstants#FN_GDB_SETUP} file and the
 * {@link SdkConstants#FN_GDBSERVER} files in addition to native libraries.
 */
public class NativeLibraryAbiPredicate implements Predicate<String> {

    /**
     * Pattern that matches ABI names. The two capture groups capture the hardware
     * platform and the file name.
     */
    private static final Pattern ABI_PATTERN = Pattern.compile("lib/([^/]+)/([^/]+)");

    /**
     * Extension that matches native libraries.
     */
    private static final String NATIVE_LIBRARY_EXTENSION = ".so";

    /**
     * Set of ABIs accepted by the predicate. If empty, then all ABIs are accepted.
     */
    @NonNull
    private Set<String> acceptedAbis;

    /**
     * Is JNI debug mode enabled?
     */
    private final boolean jniDebugMode;

    /**
     * Creates a new predicate.
     *
     * @param acceptedAbis the set of accepted ABIs; if empty then all ABIs are accepted
     * @param jniDebugMode is JNI debug mode enabled?
     */
    public NativeLibraryAbiPredicate(@NonNull Set<String> acceptedAbis, boolean jniDebugMode) {
        this.acceptedAbis = Sets.newHashSet(acceptedAbis);
        this.jniDebugMode = jniDebugMode;
    }

    @Override
    public boolean test(String input) {
        Matcher AbiMatcher = ABI_PATTERN.matcher(input);
        if (!AbiMatcher.matches()) {
            return false;
        }

        String abi = AbiMatcher.group(1);
        String fileName = AbiMatcher.group(2);

        /*
         * See if we accept the ABI.
         */
        if (!acceptedAbis.isEmpty() && !acceptedAbis.contains(abi)) {
            return false;
        }

        /*
         * If it is a shared library, accept it.
         */
        if (fileName.endsWith(NATIVE_LIBRARY_EXTENSION)) {
            return true;
        }

        /*
         * If it is not a shared library, then, if debug is enabled, we may accept a few special
         * files.
         */
        if (jniDebugMode && (SdkConstants.FN_GDBSERVER.equals(fileName) ||
                SdkConstants.FN_GDB_SETUP.equals(fileName))) {
            return true;
        }

        return false;
    }
}
