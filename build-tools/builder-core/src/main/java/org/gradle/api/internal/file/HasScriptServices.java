package org.gradle.api.internal.file;

import org.gradle.api.internal.ProcessOperations;

public interface HasScriptServices {
    FileOperations getFileOperations();

    ProcessOperations getProcessOperations();
}