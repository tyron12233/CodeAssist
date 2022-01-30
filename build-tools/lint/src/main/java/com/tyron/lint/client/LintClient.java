package com.tyron.lint.client;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.tyron.lint.api.Context;
import com.tyron.lint.api.Detector;
import com.tyron.lint.api.Issue;
import com.tyron.lint.api.Location;
import com.tyron.lint.api.Severity;
import com.tyron.lint.api.TextFormat;

public abstract class LintClient {

    /**
     * Returns an optimal detector, if applicable. By default, just returns the
     * original detector, but tools can replace detectors using this hook with a version
     * that takes advantage of native capabilities of the tool.
     *
     * @param detectorClass the class of the detector to be replaced
     * @return the new detector class, or just the original detector (not null)
     */
    @NonNull
    public Class<? extends Detector> replaceDetector(
            @NonNull Class<? extends Detector> detectorClass) {
        return detectorClass;
    }

    public boolean checkForSuppressComments() {
        return true;
    }

    /**
     * Report the given issue. This method will only be called if the configuration
     * has reported the corresponding issue as enabled and has not filtered out the issue
     * with its {@link Configuration#ignore(Context,Issue,Location,String)} method.
     * <p>
     * @param context the context used by the detector when the issue was found
     * @param issue the issue that was found
     * @param severity the severity of the issue
     * @param location the location of the issue
     * @param message the associated user message
     * @param format the format of the description and location descriptions
     */
    public abstract void report(
            @NonNull Context context,
            @NonNull Issue issue,
            @NonNull Severity severity,
            @Nullable Location location,
            @NonNull String message,
            @NonNull TextFormat format);


    public void log(Throwable t, String s, String name) {
    }
}
