package com.tyron.builder.api.internal.file.copy;


import com.tyron.builder.api.internal.file.CopyActionProcessingStreamAction;

public interface CopyActionProcessingStream {

    void process(CopyActionProcessingStreamAction action);

}