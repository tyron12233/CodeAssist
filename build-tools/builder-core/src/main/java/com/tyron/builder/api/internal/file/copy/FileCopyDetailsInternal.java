package com.tyron.builder.api.internal.file.copy;

import com.tyron.builder.api.file.FileCopyDetails;

public interface FileCopyDetailsInternal extends FileCopyDetails {

    boolean isIncludeEmptyDirs();

    boolean isDefaultDuplicatesStrategy();
}