package com.tyron.builder.api.internal.tasks.properties.annotations;

import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertyType;
import com.tyron.builder.api.tasks.OutputFiles;

import java.lang.annotation.Annotation;

public class OutputFilesPropertyAnnotationHandler extends AbstractOutputPropertyAnnotationHandler {

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return OutputFiles.class;
    }

    @Override
    protected OutputFilePropertyType getFilePropertyType() {
        return OutputFilePropertyType.FILES;
    }
}