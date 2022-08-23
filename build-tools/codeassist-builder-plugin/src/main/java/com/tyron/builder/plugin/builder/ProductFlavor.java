package com.tyron.builder.plugin.builder;

import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

public class ProductFlavor implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String mName;
    private int mMinSdkVersion = -1;
    private int mTargetSdkVersion = -1;
    private int mVersionCode = -1;
    private String mVersionName = null;
    private String mPackageName = null;
    private String mTestPackageName = null;
    private String mTestInstrumentationRunner = null;
    private String mSigningStoreLocation = null;
    private String mSigningStorePassword = null;
    private String mSigningKeyAlias = null;
    private String mSigningKeyPassword = null;
    private final List<String> mBuildConfigLines = Lists.newArrayList();

    public ProductFlavor(String name) {
        mName = name;
    }

    public String getName() {
        return mName;
    }

    public void setPackageName(String packageName) {
        mPackageName = packageName;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public void setVersionCode(int versionCode) {
        mVersionCode = versionCode;
    }

    public int getVersionCode() {
        return mVersionCode;
    }

    public void setVersionName(String versionName) {
        mVersionName = versionName;
    }

    public String getVersionName() {
        return mVersionName;
    }

    public void setMinSdkVersion(int minSdkVersion) {
        mMinSdkVersion = minSdkVersion;
    }

    public int getMinSdkVersion() {
        return mMinSdkVersion;
    }

    public void setTargetSdkVersion(int targetSdkVersion) {
        mTargetSdkVersion = targetSdkVersion;
    }

    public int getTargetSdkVersion() {
        return mTargetSdkVersion;
    }

    public void setTestPackageName(String testPackageName) {
        mTestPackageName = testPackageName;
    }

    public String getTestPackageName() {
        return mTestPackageName;
    }

    public void setTestInstrumentationRunner(String testInstrumentationRunner) {
        mTestInstrumentationRunner = testInstrumentationRunner;
    }

    public String getTestInstrumentationRunner() {
        return mTestInstrumentationRunner;
    }

    public String getSigningStoreLocation() {
        return mSigningStoreLocation;
    }

    public void setSigningStoreLocation(String signingStoreLocation) {
        mSigningStoreLocation = signingStoreLocation;
    }

    public String getSigningStorePassword() {
        return mSigningStorePassword;
    }

    public void setSigningStorePassword(String signingStorePassword) {
        mSigningStorePassword = signingStorePassword;
    }

    public String getSigningKeyAlias() {
        return mSigningKeyAlias;
    }

    public void setSigningKeyAlias(String signingKeyAlias) {
        mSigningKeyAlias = signingKeyAlias;
    }

    public String getSigningKeyPassword() {
        return mSigningKeyPassword;
    }

    public void setSigningKeyPassword(String signingKeyPassword) {
        mSigningKeyPassword = signingKeyPassword;
    }

    public boolean isSigningReady() {
        return mSigningStoreLocation != null &&
               mSigningStorePassword != null &&
               mSigningKeyAlias != null &&
               mSigningKeyPassword != null;
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

    /**
     * Merges the flavor on top of a base platform and returns a new object with the result.
     *
     * @param base the flavor to merge on top of
     * @return a new merged product flavor
     */
    ProductFlavor mergeOver(@NotNull ProductFlavor base) {
        ProductFlavor flavor = new ProductFlavor("");
        flavor.mMinSdkVersion = chooseInt(mMinSdkVersion, base.mMinSdkVersion);
        flavor.mTargetSdkVersion = chooseInt(mTargetSdkVersion, base.mTargetSdkVersion);
        flavor.mVersionCode = chooseInt(mVersionCode, base.mVersionCode);
        flavor.mVersionName = chooseString(mVersionName, base.mVersionName);
        flavor.mPackageName = chooseString(mPackageName, base.mPackageName);
        flavor.mTestPackageName = chooseString(mTestPackageName, base.mTestPackageName);
        flavor.mTestInstrumentationRunner =
                chooseString(mTestInstrumentationRunner, base.mTestInstrumentationRunner);
        flavor.mSigningStoreLocation =
                chooseString(mSigningStoreLocation, base.mSigningStoreLocation);
        flavor.mSigningStorePassword =
                chooseString(mSigningStorePassword, base.mSigningStorePassword);
        flavor.mSigningKeyAlias = chooseString(mSigningKeyAlias, base.mSigningKeyAlias);
        flavor.mSigningKeyPassword = chooseString(mSigningKeyPassword, base.mSigningKeyPassword);
        return flavor;
    }

    private int chooseInt(int overlay, int base) {
        return overlay != -1 ? overlay : base;
    }

    private String chooseString(String overlay, String base) {
        return overlay != null ? overlay : base;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ProductFlavor that = (ProductFlavor) o;
        if (!Objects.equals(mName, that.mName)) {
            return false;
        }
        if (mMinSdkVersion != that.mMinSdkVersion) {
            return false;
        }
        if (mTargetSdkVersion != that.mTargetSdkVersion) {
            return false;
        }
        if (mVersionCode != that.mVersionCode) {
            return false;
        }
        if (!Objects.equals(mBuildConfigLines, that.mBuildConfigLines)) {
            return false;
        }
        if (!Objects.equals(mPackageName, that.mPackageName)) {
            return false;
        }
        if (!Objects.equals(mSigningKeyAlias, that.mSigningKeyAlias)) {
            return false;
        }
        if (!Objects.equals(mSigningKeyPassword, that.mSigningKeyPassword)) {
            return false;
        }
        if (!Objects.equals(mSigningStoreLocation, that.mSigningStoreLocation)) {
            return false;
        }
        if (!Objects.equals(mSigningStorePassword, that.mSigningStorePassword)) {
            return false;
        }
        if (!Objects.equals(mTestInstrumentationRunner, that.mTestInstrumentationRunner)) {
            return false;
        }
        if (!Objects.equals(mTestPackageName, that.mTestPackageName)) {
            return false;
        }
        return Objects.equals(mVersionName, that.mVersionName);
    }

    @Override
    public int hashCode() {
        int result = mName != null ? mName.hashCode() : 0;
        result = 31 * result + mMinSdkVersion;
        result = 31 * result + mTargetSdkVersion;
        result = 31 * result + mVersionCode;
        result = 31 * result + (mVersionName != null ? mVersionName.hashCode() : 0);
        result = 31 * result + (mPackageName != null ? mPackageName.hashCode() : 0);
        result = 31 * result + (mTestPackageName != null ? mTestPackageName.hashCode() : 0);
        result = 31 * result +
                 (mTestInstrumentationRunner != null ? mTestInstrumentationRunner.hashCode() : 0);
        result = 31 * result +
                 (mSigningStoreLocation != null ? mSigningStoreLocation.hashCode() : 0);
        result = 31 * result +
                 (mSigningStorePassword != null ? mSigningStorePassword.hashCode() : 0);
        result = 31 * result + (mSigningKeyAlias != null ? mSigningKeyAlias.hashCode() : 0);
        result = 31 * result + (mSigningKeyPassword != null ? mSigningKeyPassword.hashCode() : 0);
        result = 31 * result + (mBuildConfigLines != null ? mBuildConfigLines.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", mName).add("minSdkVersion", mMinSdkVersion)
                .add("targetSdkVersion", mTargetSdkVersion).add("versionCode", mVersionCode)
                .add("versionName", mVersionName).add("packageName", mPackageName)
                .add("testPackageName", mTestPackageName)
                .add("testInstrumentationRunner", mTestInstrumentationRunner)
                .add("signingStoreLocation", mSigningStoreLocation)
                .add("signingStorePassword", mSigningStorePassword)
                .add("signingKeyAlias", mSigningKeyAlias)
                .add("signingKeyPassword", mSigningKeyPassword).omitNullValues().toString();
    }
    /*
        release signing info (keystore, key alias, passwords,...).
        native abi filter
    */
}