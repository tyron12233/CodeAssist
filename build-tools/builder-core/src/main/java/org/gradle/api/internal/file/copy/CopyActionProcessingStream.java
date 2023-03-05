package org.gradle.api.internal.file.copy;


import org.gradle.api.internal.file.CopyActionProcessingStreamAction;

public interface CopyActionProcessingStream {

    void process(CopyActionProcessingStreamAction action);

}