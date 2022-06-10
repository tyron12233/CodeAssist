package org.gradle.internal.fingerprint.hashing;

import org.gradle.internal.file.archive.ZipEntry;

import java.util.function.Supplier;

public interface ZipEntryContext {
    ZipEntry getEntry();

    String getFullName();

    String getRootParentName();

    Supplier<String[]> getRelativePathSegments();
}
