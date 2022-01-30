package com.tyron.lint.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Severity of an issue found by lint
 */
public enum Severity {
    /**
     * Fatal: Use sparingly because a warning marked as fatal will be
     * considered critical and will abort Export APK etc in ADT
     */
    @NonNull
    FATAL("Fatal"),

    /**
     * Errors: The issue is known to be a real error that must be addressed.
     */
    @NonNull
    ERROR("Error"),

    /**
     * Warning: Probably a problem.
     */
    @NonNull
    WARNING("Warning"),

    /**
     * Information only: Might not be a problem, but the check has found
     * something interesting to say about the code.
     */
    @NonNull
    INFORMATIONAL("Information"),

    /**
     * Ignore: The user doesn't want to see this issue
     */
    @NonNull
    IGNORE("Ignore");

    @NonNull
    private final String mDisplay;

    Severity(@NonNull String display) {
        mDisplay = display;
    }

    /**
     * Returns a description of this severity suitable for display to the user
     *
     * @return a description of the severity
     */
    @NonNull
    public String getDescription() {
        return mDisplay;
    }

    /** Returns the name of this severity */
    @NonNull
    public String getName() {
        return name();
    }

    /**
     * Looks up the severity corresponding to a given named severity. The severity
     * string should be one returned by {@link #toString()}
     *
     * @param name the name to look up
     * @return the corresponding severity, or null if it is not a valid severity name
     */
    @Nullable
    public static Severity fromName(@NonNull String name) {
        for (Severity severity : values()) {
            if (severity.name().equalsIgnoreCase(name)) {
                return severity;
            }
        }

        return null;
    }
}