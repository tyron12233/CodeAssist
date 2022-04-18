package com.tyron.builder.api.internal.file.copy;


import com.tyron.builder.api.tasks.WorkResult;

public interface CopyAction {

    WorkResult execute(CopyActionProcessingStream stream);

}