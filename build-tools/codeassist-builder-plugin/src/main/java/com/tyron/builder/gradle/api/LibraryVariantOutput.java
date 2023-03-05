package com.tyron.builder.gradle.api;

import com.android.annotations.Nullable;
import org.gradle.api.tasks.bundling.Zip;

/** A variant output for library variants. */
@Deprecated
public interface LibraryVariantOutput {

    /** Returns the Library AAR packaging task. */
    @Nullable
    @Deprecated
    Zip getPackageLibrary();
}