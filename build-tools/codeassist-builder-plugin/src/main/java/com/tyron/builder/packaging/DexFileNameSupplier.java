package com.tyron.builder.packaging;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.google.common.base.Supplier;
import java.util.Locale;

/** Supplier that provides sequential names for dex files. */
public class DexFileNameSupplier implements Supplier<String> {

    /** Current dex-file index. */
    private int mIndex;

    /** Creates a new renamer. */
    public DexFileNameSupplier() {
        mIndex = 1;
    }

    @Override
    @NonNull
    public String get() {
        String dexFileName;
        if (mIndex == 1) {
            dexFileName = SdkConstants.FN_APK_CLASSES_DEX;
        } else {
            dexFileName = String.format(Locale.US, SdkConstants.FN_APK_CLASSES_N_DEX, mIndex);
        }

        mIndex++;
        return dexFileName;
    }
}
