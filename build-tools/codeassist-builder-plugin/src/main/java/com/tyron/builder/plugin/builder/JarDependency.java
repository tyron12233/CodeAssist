package com.tyron.builder.plugin.builder;

/**
 * Represents a Jar dependency. This could be the output of a Java project.
 */
public class JarDependency {
    private final String mLocation;
    private final boolean mCompiled;
    private final boolean mPackaged;
    private final boolean mProguarded;

    public JarDependency(String location, boolean compiled, boolean packaged, boolean proguarded) {
        mLocation = location;
        mCompiled = compiled;
        mPackaged = packaged;
        mProguarded = proguarded;
    }

    public String getLocation() {
        return mLocation;
    }

    public boolean isCompiled() {
        return mCompiled;
    }

    public boolean isPackaged() {
        return mPackaged;
    }

    public boolean isProguarded() {
        return mProguarded;
    }
}