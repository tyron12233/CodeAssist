package com.tyron.builder.jvm.toolchain.internal;

import com.tyron.builder.jvm.toolchain.JavaLanguageVersion;

import java.io.Serializable;

public class DefaultJavaLanguageVersion implements JavaLanguageVersion, Serializable {

    static final int LOWER_CACHED_VERSION = 4;
    static final int HIGHER_CACHED_VERSION = 19;
    static final JavaLanguageVersion[] KNOWN_VERSIONS;

    static {
        KNOWN_VERSIONS = new JavaLanguageVersion[HIGHER_CACHED_VERSION - LOWER_CACHED_VERSION + 1];
        for (int version = LOWER_CACHED_VERSION; version <= HIGHER_CACHED_VERSION; version++) {
            KNOWN_VERSIONS[version - LOWER_CACHED_VERSION] = new DefaultJavaLanguageVersion(version);
        }
    }

    public static JavaLanguageVersion of(int version) {
        if (version <= 0) {
            throw new IllegalArgumentException("JavaLanguageVersion must be a positive integer, not " + version);
        }
        if (version >= LOWER_CACHED_VERSION && version <= HIGHER_CACHED_VERSION) {
            return KNOWN_VERSIONS[version - LOWER_CACHED_VERSION];
        } else {
            return new DefaultJavaLanguageVersion(version);
        }
    }

    private final int version;

    private DefaultJavaLanguageVersion(int version) {
        this.version = version;
    }

    @Override
    public int asInt() {
        return version;
    }

    @Override
    public String toString() {
        if (version < 5) {
            return String.format("1.%d", version);
        }
        return Integer.toString(version);
    }

    @Override
    public boolean canCompileOrRun(JavaLanguageVersion other) {
        return compareTo(other) >= 0;
    }

    @Override
    public int compareTo(JavaLanguageVersion other) {
        return Integer.compare(version, other.asInt());
    }

    @Override
    public boolean canCompileOrRun(int otherVersion) {
        return version >= otherVersion;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DefaultJavaLanguageVersion that = (DefaultJavaLanguageVersion) o;
        return version == that.version;
    }

    @Override
    public int hashCode() {
        return version;
    }
}
