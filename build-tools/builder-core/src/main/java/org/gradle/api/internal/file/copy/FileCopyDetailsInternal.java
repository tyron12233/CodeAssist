package org.gradle.api.internal.file.copy;

import org.gradle.api.file.FileCopyDetails;

public interface FileCopyDetailsInternal extends FileCopyDetails {

    boolean isIncludeEmptyDirs();

    boolean isDefaultDuplicatesStrategy();
}