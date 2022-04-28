package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.internal.file.copy.FileCopyDetailsInternal;

public interface CopyActionProcessingStreamAction {

    void processFile(FileCopyDetailsInternal details);

}