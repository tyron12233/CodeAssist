package com.tyron.builder.packaging

import com.android.SdkConstants
import java.io.File

/** Comparator that compares dex file paths, placing classes.dex always in front. */
class DexFileComparator : Comparator<File> {

    override fun compare(f1: File, f2: File): Int {
        return if (f1.name.endsWith(SdkConstants.FN_APK_CLASSES_DEX)) {
            if (f2.name.endsWith(SdkConstants.FN_APK_CLASSES_DEX)) {
                f1.absolutePath.compareTo(f2.absolutePath)
            } else {
                -1
            }
        } else {
            if (f2.name.endsWith(SdkConstants.FN_APK_CLASSES_DEX)) {
                1
            } else {
               return f1
                    .absolutePath
                    .compareTo(f2.absolutePath)
            }
        }
    }
}