package com.tyron.builder.testing.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Provider of a test device characteristics.
 */
public interface DeviceConfigProvider {

    @NonNull
    String getConfigFor(String abi);

    int getDensity();

    @Nullable
    String getLanguage();

    @Nullable
    default Set<String> getLanguageSplits() {
        return null;
    }

    @Nullable
    String getRegion();

    @NonNull
    List<String> getAbis();

    @Nullable
    default String getApiCodeName() {
        return null;
    }

    default int getApiLevel() {
        return 1;
    }
}
