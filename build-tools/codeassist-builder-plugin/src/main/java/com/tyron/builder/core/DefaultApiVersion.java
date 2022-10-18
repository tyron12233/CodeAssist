package com.tyron.builder.core;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.model.ApiVersion;
import com.android.sdklib.SdkVersionInfo;

/** Basic implementation of ApiVersion */
public final class DefaultApiVersion implements ApiVersion {

    private final int mApiLevel;

    @Nullable
    private final String mCodename;

    public DefaultApiVersion(int apiLevel) {
        mApiLevel = apiLevel;
        mCodename = null;
    }

    /**
     * API version for a preview Android Version.
     *
     * <p>Preview versions will have their true api level, i.e. the same as the previous stable
     * version.
     */
    public DefaultApiVersion(@NonNull String codename) {
        mApiLevel = SdkVersionInfo.getApiByBuildCode(codename, true) - 1;
        mCodename = codename;
    }

    @NonNull
    public static ApiVersion create(@NonNull Object value) {
        if (value instanceof Integer) {
            return new DefaultApiVersion((Integer) value);
        } else if (value instanceof String) {
            return new DefaultApiVersion((String) value);
        }

        return new DefaultApiVersion(1);
    }

    @Override
    public int getApiLevel() {
        return mApiLevel;
    }

    @Nullable
    @Override
    public String getCodename() {
        return mCodename;
    }

    @NonNull
    @Override
    public String getApiString() {
        return mCodename != null ? mCodename : Integer.toString(mApiLevel);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        DefaultApiVersion that = (DefaultApiVersion) o;

        if (mApiLevel != that.mApiLevel) {
            return false;
        }
        if (mCodename != null ? !mCodename.equals(that.mCodename) : that.mCodename != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = mApiLevel;
        result = 31 * result + (mCodename != null ? mCodename.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DefaultApiVersion{" +
                "mApiLevel=" + mApiLevel +
                ", mCodename='" + mCodename + '\'' +
                '}';
    }
}