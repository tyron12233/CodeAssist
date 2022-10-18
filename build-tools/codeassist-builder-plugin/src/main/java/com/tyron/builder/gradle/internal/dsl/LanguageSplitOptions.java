package com.tyron.builder.gradle.internal.dsl;

import com.android.annotations.NonNull;
import com.google.common.collect.Sets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Encapsulates settings for <a
 * href="https://developer.android.com/studio/build/configure-apk-splits.html#configure-abi-split">
 * building per-language (or locale) APKs</a>.
 *
 * <p><b>Note:</b> Building per-language APKs is supported only when <a
 * href="https://developer.android.com/topic/instant-apps/guides/config-splits.html">building
 * configuration APKs</a> for <a
 * href="https://developer.android.com/topic/instant-apps/index.html">Android Instant Apps</a>.
 */
public class LanguageSplitOptions {

    private boolean enable = false;
    private Set<String> include;

    /**
     * Collection of include patterns.
     */
    public Set<String> getInclude() {
        return include;
    }

    public void setInclude(@NonNull List<String> list) {
        include = Sets.newHashSet(list);
    }

    /**
     * Adds an include pattern.
     */
    public void include(@NonNull String... includes) {
        if (include == null) {
            include = Sets.newHashSet(includes);
            return;
        }

        include.addAll(Arrays.asList(includes));
    }

    @NonNull
    public Set<String> getApplicationFilters() {
        return include == null || !enable ? new HashSet<>() : include;
    }

    /**
     * enables or disables splits for language
     */
    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    /**
     * Returns true if splits should be generated for languages.
     */
    public boolean isEnable() {
        return enable;
    }

    /**
     * Sets whether the build system should determine the splits based on the "language-*" folders
     * in the resources.
     *
     * <p>If the auto mode is set to true, the include list will be ignored.
     *
     * @param auto true to automatically set the splits list based on the folders presence, false
     *             to use the include list.
     *
     * @deprecated LanguageSplitOptions.auto is not supported anymore.
     */
    @Deprecated
    public void setAuto(boolean auto) {
        throw new RuntimeException("LanguageSplitOptions.auto is not supported anymore.");
    }
}
