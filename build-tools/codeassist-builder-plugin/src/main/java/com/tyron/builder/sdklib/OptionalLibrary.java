package com.tyron.builder.sdklib;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.sdklib.repository.meta.DetailsTypes;
import java.nio.file.Path;

/** An optional library provided by an Android Target */
public interface OptionalLibrary {
    /** The name of the library, as used in the manifest (&lt;uses-library&gt;). */
    @NonNull
    String getName();

    /**
     * Location of the jar file. Should never be {@code null} when retrieved from a target, but may
     * be in some cases when retrieved from an {@link DetailsTypes.AddonDetailsType}.
     */
    @Nullable
    Path getJar();

    /** Description of the library. */
    @NonNull
    String getDescription();

    /** Whether the library requires a manifest entry */
    boolean isManifestEntryRequired();

    /**
     * Path to the library jar file relative to the {@code libs} directory in the package. Can be
     * {@code null} when retrieved from a {@link LocalPackage} that was installed from a legacy
     * source.
     */
    @Nullable
    String getLocalJarPath();
}
