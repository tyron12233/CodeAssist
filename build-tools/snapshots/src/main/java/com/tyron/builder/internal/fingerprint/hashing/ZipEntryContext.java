package com.tyron.builder.internal.fingerprint.hashing;

import com.tyron.builder.internal.file.archive.ZipEntry;

import java.util.function.Supplier;

public interface ZipEntryContext {
    ZipEntry getEntry();

    String getFullName();

    String getRootParentName();

    Supplier<String[]> getRelativePathSegments();
}
