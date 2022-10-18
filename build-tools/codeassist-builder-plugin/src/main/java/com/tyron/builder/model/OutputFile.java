package com.tyron.builder.model;

import org.jetbrains.annotations.NotNull;

import java.io.File;

/** An output with an associated set of filters. */
@Deprecated
public interface OutputFile extends VariantOutput {

    /**
     * Returns the output file for this artifact's output.
     * Depending on whether the project is an app or a library project, this could be an apk or
     * an aar file. If this {@link com.android.build.OutputFile} has filters, this is a split
     * APK.
     *
     * For test artifact for a library project, this would also be an apk.
     *
     * @return the output file.
     */
    @NotNull
    File getOutputFile();
}