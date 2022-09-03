package com.tyron.builder;

import com.android.annotations.NonNull;
import java.util.Collection;

/** basic variant output information */
@Deprecated
public interface VariantOutput {

    /** An object representing the lack of filter. */
    String NO_FILTER = null;

    /**
     * Type of package file, either the main APK or a full split APK file containing resources for a
     * particular split dimension.
     */
    enum OutputType {
        MAIN,
        FULL_SPLIT
    }

    /**
     * String representation of the OutputType enum which can be used for remote-able interfaces.
     */
    String MAIN = OutputType.MAIN.name();

    String FULL_SPLIT = OutputType.FULL_SPLIT.name();

    /** Split dimension type */
    enum FilterType {
        DENSITY,
        ABI,
        LANGUAGE
    }

    /**
     * String representations of the FilterType enum which can be used for remote-able interfaces.Ap
     */
    String DENSITY = FilterType.DENSITY.name();

    String ABI = FilterType.ABI.name();
    String LANGUAGE = FilterType.LANGUAGE.name();

    /** Returns the output type of the referenced APK. */
    @NonNull
    String getOutputType();

    /**
     * Returns the split dimensions the referenced APK was created with. Each collection's value is
     * the string representation of an element of the {@link FilterType} enum.
     */
    @NonNull
    Collection<String> getFilterTypes();

    /** Returns all the split information used to create the APK. */
    @NonNull
    Collection<FilterData> getFilters();

    /**
     * Returns the main file for this artifact which can be either the {@link
     * com.android.build.OutputFile.OutputType#MAIN} or {@link
     * com.android.build.OutputFile.OutputType#FULL_SPLIT}
     */
    @NonNull
    OutputFile getMainOutputFile();

    /**
     * All the output files for this artifacts, contains the main APK and optionally a list of split
     * APKs.
     */
    @NonNull
    @Deprecated
    Collection<? extends OutputFile> getOutputs();

    /**
     * Returns the version code for this output.
     *
     * <p>This is convenient method that returns the final version code whether it's coming from the
     * override set in the output or from the variant's merged flavor.
     *
     * @return the version code.
     */
    int getVersionCode();
}