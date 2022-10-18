package org.gradle.api.internal.file.copy;


import org.gradle.api.tasks.WorkResult;

public interface CopyAction {

    WorkResult execute(CopyActionProcessingStream stream);

}