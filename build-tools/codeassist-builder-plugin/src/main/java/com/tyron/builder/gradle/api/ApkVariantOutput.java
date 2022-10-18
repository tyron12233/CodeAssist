package com.tyron.builder.gradle.api;

import com.android.annotations.Nullable;
import com.tyron.builder.OutputFile;
import org.gradle.api.Task;

/** A variant output for apk-generating variants. */
@Deprecated
public interface ApkVariantOutput extends BaseVariantOutput {

    /**
     * Returns the packaging tas
     *
     * @deprecated Use {@link
     *     com.tyron.builder.gradle.api.ApkVariant#getPackageApplicationProvider()}
     */
    @Nullable
    @Deprecated
    Task getPackageApplication();

    /**
     * Returns the Zip align task.
     *
     * @deprecated This returns the packaging task as it now does the zip-align step.
     */
    @Nullable
    @Deprecated
    Task getZipAlign();

    /**
     * Sets the version code override. This version code will only affect this output.
     *
     * If the value is -1, then the output will use the version code defined in the main
     * merged flavors for this variant.
     *
     * @param versionCodeOverride the version code override.
     */
    void setVersionCodeOverride(int versionCodeOverride);

    /**
     * Returns the version code override.
     *
     * If the value is -1, then the output will use the version code defined in the main
     * merged flavors for this variant.
     *
     * @return the version code override.
     */
    int getVersionCodeOverride();

    /**
     * Sets the version name override. This version name will only affect this output.
     *
     * If the value is null, then the output will use the version name defined in the main
     * merged flavors for this variant.
     *
     * @param versionNameOverride the version name override.
     */
    void setVersionNameOverride(String versionNameOverride);

    /**
     * Returns the version name override.
     *
     * If the value is null, then the output will use the version name defined in the main
     * merged flavors for this variant.
     *
     * @return the version name override.
     */
    String getVersionNameOverride();

    /**
     * Returns a filter value for a filter type if present on this variant or null otherwise.
     *
     * @param filterType the type of the filter requested.
     * @return the filter value.
     */
    String getFilter(OutputFile.FilterType filterType);

    /** Returns the output file name for this variant output. */
    String getOutputFileName();

    /**
     * Sets the output file name for this variant output.
     *
     * @param outputFileName the new file name.
     */
    void setOutputFileName(String outputFileName);
}
