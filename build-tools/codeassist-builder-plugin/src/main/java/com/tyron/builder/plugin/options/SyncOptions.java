package com.tyron.builder.plugin.options;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.gradle.options.BooleanOption;
import com.tyron.builder.gradle.options.IntegerOption;
import com.tyron.builder.gradle.options.ProjectOptions;
import com.tyron.builder.model.AndroidProject;

public final class SyncOptions {

    public enum ErrorFormatMode {
        MACHINE_PARSABLE,
        HUMAN_READABLE
    }

    public enum EvaluationMode {
        /** Standard mode, errors should be breaking */
        STANDARD,
        /** IDE mode. Errors should not be breaking and should generate a SyncIssue instead. */
        IDE,
    }

    private SyncOptions() {}

    public static EvaluationMode getModelQueryMode(@NonNull ProjectOptions options) {
        if (options.get(BooleanOption.IDE_BUILD_MODEL_ONLY_ADVANCED)
            || options.get(BooleanOption.IDE_BUILD_MODEL_ONLY_V2)) {
            return EvaluationMode.IDE;
        }

        return EvaluationMode.STANDARD;
    }

    public static ErrorFormatMode getErrorFormatMode(@NonNull ProjectOptions options) {
        if (options.get(BooleanOption.IDE_INVOKED_FROM_IDE)) {
            return ErrorFormatMode.MACHINE_PARSABLE;
        } else {
            return ErrorFormatMode.HUMAN_READABLE;
        }
    }

    /**
     * Returns the level of model-only mode.
     *
     * <p>The model-only mode is triggered when the IDE does a sync, and therefore we do things a
     * bit differently (don't throw exceptions for instance). Things evolved a bit over versions and
     * the behavior changes. This reflects the mode to use.
     *
     * @param options the project options
     * @return an integer or null if we are not in model-only mode.
     * @see AndroidProject#MODEL_LEVEL_0_ORIGINAL
     * @see AndroidProject#MODEL_LEVEL_1_SYNC_ISSUE
     * @see AndroidProject#MODEL_LEVEL_3_VARIANT_OUTPUT_POST_BUILD
     * @see AndroidProject#MODEL_LEVEL_4_NEW_DEP_MODEL
     */
    @Nullable
    public static Integer buildModelOnlyVersion(@NonNull ProjectOptions options) {
        if (options.get(IntegerOption.IDE_BUILD_MODEL_ONLY_VERSION) != null) {
            return options.get(IntegerOption.IDE_BUILD_MODEL_ONLY_VERSION);
        }

        if (options.get(BooleanOption.IDE_BUILD_MODEL_ONLY_ADVANCED)) {
            return AndroidProject.MODEL_LEVEL_1_SYNC_ISSUE;
        }

        if (options.get(BooleanOption.IDE_BUILD_MODEL_ONLY)) {
            return AndroidProject.MODEL_LEVEL_0_ORIGINAL;
        }

        return null;
    }
}
