package org.gradle.api.internal.file;

import org.gradle.api.internal.file.copy.FileCopyDetailsInternal;

public interface CopyActionProcessingStreamAction {

    void processFile(FileCopyDetailsInternal details);

}