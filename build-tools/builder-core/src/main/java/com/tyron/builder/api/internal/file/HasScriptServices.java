package com.tyron.builder.api.internal.file;

import com.tyron.builder.api.internal.ProcessOperations;

public interface HasScriptServices {
    FileOperations getFileOperations();

    ProcessOperations getProcessOperations();
}