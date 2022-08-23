package com.tyron.builder.plugin.builder;

import com.google.common.base.MoreObjects;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class BuildType implements Serializable {
    private static final long serialVersionUID = 1L;
    public final static String DEBUG = "debug";
    public final static String RELEASE = "release";
    private final String mName;
    private boolean mDebuggable;
    private boolean mDebugJniBuild;
    private boolean mDebugSigned;
    private String mPackageNameSuffix = null;
    private boolean mRunProguard = false;
    private final List<String> mBuildConfigLines = new ArrayList<String>();
    private boolean mZipAlign = true;

    public BuildType(@NotNull String name) {
        this.mName = name;
        if (DEBUG.equals(name)) {
            initDebug();
        } else if (RELEASE.equals(name)) {
            initRelease();
        }
    }

    private void initDebug() {
        mDebuggable = true;
        mDebugJniBuild = true;
        mDebugSigned = true;
        mZipAlign = false;
    }

    private void initRelease() {
        mDebuggable = false;
        mDebugJniBuild = false;
        mDebugSigned = false;
    }

    public String getName() {
        return mName;
    }

    public void setDebuggable(boolean debuggable) {
        mDebuggable = debuggable;
    }

    public boolean isDebuggable() {
        return mDebuggable;
    }

    public void setDebugJniBuild(boolean debugJniBuild) {
        mDebugJniBuild = debugJniBuild;
    }

    public boolean isDebugJniBuild() {
        return mDebugJniBuild;
    }

    public void setDebugSigned(boolean debugSigned) {
        mDebugSigned = debugSigned;
    }

    public boolean isDebugSigned() {
        return mDebugSigned;
    }

    public void setPackageNameSuffix(@Nullable String packageNameSuffix) {
        mPackageNameSuffix = packageNameSuffix;
    }

    @Nullable
    public String getPackageNameSuffix() {
        return mPackageNameSuffix;
    }

    public void setRunProguard(boolean runProguard) {
        mRunProguard = runProguard;
    }

    public boolean isRunProguard() {
        return mRunProguard;
    }

    public void setZipAlign(boolean zipAlign) {
        mZipAlign = zipAlign;
    }

    public boolean isZipAlign() {
        return mZipAlign;
    }

    protected void addBuildConfigLines(List<String> lines) {
        mBuildConfigLines.addAll(lines);
    }

    protected void clearBuildConfigLines() {
        mBuildConfigLines.clear();
    }

    public List<String> getBuildConfigLines() {
        return mBuildConfigLines;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        BuildType buildType = (BuildType) o;
        if (mName != null ? !mName.equals(buildType.mName) : buildType.mName != null) {
            return false;
        }
        if (mDebugJniBuild != buildType.mDebugJniBuild) {
            return false;
        }
        if (mDebugSigned != buildType.mDebugSigned) {
            return false;
        }
        if (mDebuggable != buildType.mDebuggable) {
            return false;
        }
        if (mRunProguard != buildType.mRunProguard) {
            return false;
        }
        if (mZipAlign != buildType.mZipAlign) {
            return false;
        }
        if (mBuildConfigLines != null ? !mBuildConfigLines.equals(buildType.mBuildConfigLines) :
                buildType.mBuildConfigLines !=
                null) {
            return false;
        }
        if (!Objects.equals(mPackageNameSuffix, buildType.mPackageNameSuffix)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = mName != null ? mName.hashCode() : 0;
        result = 31 * result + (mDebuggable ? 1 : 0);
        result = 31 * result + (mDebugJniBuild ? 1 : 0);
        result = 31 * result + (mDebugSigned ? 1 : 0);
        result = 31 * result + (mPackageNameSuffix != null ? mPackageNameSuffix.hashCode() : 0);
        result = 31 * result + (mRunProguard ? 1 : 0);
        result = 31 * result + (mBuildConfigLines != null ? mBuildConfigLines.hashCode() : 0);
        result = 31 * result + (mZipAlign ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", mName).add("debuggable", mDebuggable)
                .add("debugJniBuild", mDebugJniBuild).add("debugSigned", mDebugSigned)
                .add("packageNameSuffix", mPackageNameSuffix).add("runProguard", mRunProguard)
                .add("zipAlign", mZipAlign).omitNullValues().toString();
    }
}