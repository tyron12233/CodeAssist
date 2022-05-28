package com.tyron.builder.cache.internal;

import com.google.common.base.Preconditions;
import com.tyron.builder.util.GradleVersion;

import java.io.File;

import javax.annotation.Nonnull;

public class VersionSpecificCacheDirectory implements Comparable<VersionSpecificCacheDirectory> {

    private final File dir;
    private final GradleVersion version;

    public VersionSpecificCacheDirectory(File dir, GradleVersion version) {
        this.dir = Preconditions.checkNotNull(dir, "dir must not be null");
        this.version = Preconditions.checkNotNull(version, "version must not be null");
    }

    public File getDir() {
        return dir;
    }

    public GradleVersion getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        VersionSpecificCacheDirectory that = (VersionSpecificCacheDirectory) o;
        return this.dir.equals(that.dir) && this.version.equals(that.version);
    }

    @Override
    public int hashCode() {
        int result = dir.hashCode();
        result = 31 * result + version.hashCode();
        return result;
    }

    @Override
    public int compareTo(@Nonnull VersionSpecificCacheDirectory that) {
        return this.version.compareTo(that.version);
    }

}
