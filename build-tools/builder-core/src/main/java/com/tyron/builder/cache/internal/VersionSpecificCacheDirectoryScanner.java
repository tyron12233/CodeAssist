package com.tyron.builder.cache.internal;

import static org.apache.commons.io.filefilter.FileFilterUtils.directoryFileFilter;

import com.google.common.collect.ImmutableSortedSet;
import com.tyron.builder.util.GradleVersion;

import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.RegexFileFilter;

import java.io.File;
import java.io.FileFilter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.SortedSet;

import javax.annotation.Nullable;

public class VersionSpecificCacheDirectoryScanner {

    private final File baseDir;

    public VersionSpecificCacheDirectoryScanner(File baseDir) {
        this.baseDir = baseDir;
    }

    public File getBaseDir() {
        return baseDir;
    }

    public File getDirectory(GradleVersion gradleVersion) {
        return new File(baseDir, gradleVersion.getVersion());
    }

    public SortedSet<VersionSpecificCacheDirectory> getExistingDirectories() {
        ImmutableSortedSet.Builder<VersionSpecificCacheDirectory> builder =
                ImmutableSortedSet.naturalOrder();
        for (File subDir : listVersionSpecificCacheDirs()) {
            GradleVersion version = tryParseGradleVersion(subDir);
            if (version != null) {
                builder.add(new VersionSpecificCacheDirectory(subDir, version));
            }
        }
        return builder.build();
    }

    private Collection<File> listVersionSpecificCacheDirs() {
        FileFilter combinedFilter =
                FileFilterUtils.and(directoryFileFilter(), new RegexFileFilter("^\\d.*"));
        File[] result = baseDir.listFiles(combinedFilter);
        return result == null ? Collections.<File>emptySet() : Arrays.asList(result);
    }

    @Nullable
    private GradleVersion tryParseGradleVersion(File dir) {
        try {
            return GradleVersion.version(dir.getName());
        } catch (Exception e) {
            return null;
        }
    }
}
