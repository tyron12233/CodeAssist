package com.tyron.builder.gradle.internal.ide;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.tyron.builder.model.ApiVersion;
import com.android.sdklib.AndroidVersion;
import java.io.Serializable;
import java.util.Objects;

/**
 * Implementation of ApiVersion that is serializable so that it can be used in the
 * model returned by the Gradle plugin.
 **/
@Immutable
final class ApiVersionImpl implements ApiVersion, Serializable {
    private static final long serialVersionUID = 1L;

    private final int mApiLevel;
    @Nullable
    private final String mCodename;

    @Nullable
    public static ApiVersion clone(@Nullable ApiVersion apiVersion) {
        if (apiVersion == null) {
            return null;
        }

        return new ApiVersionImpl(apiVersion);
    }

    public static ApiVersion clone(@NonNull AndroidVersion androidVersion) {
        return new ApiVersionImpl(androidVersion.getApiLevel(), androidVersion.getCodename());
    }

    private ApiVersionImpl(@NonNull ApiVersion apiVersion) {
        this(apiVersion.getApiLevel(), apiVersion.getCodename());
    }

    private ApiVersionImpl(int apiLevel, @Nullable String codename) {
        mApiLevel = apiLevel;
        mCodename = codename;
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
        ApiVersionImpl that = (ApiVersionImpl) o;
        return mApiLevel == that.mApiLevel &&
                Objects.equals(mCodename, that.mCodename);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mApiLevel, mCodename);
    }
}
