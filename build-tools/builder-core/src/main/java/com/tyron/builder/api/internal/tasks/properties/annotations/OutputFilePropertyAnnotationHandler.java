package com.tyron.builder.api.internal.tasks.properties.annotations;

import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertyType;
import com.tyron.builder.api.tasks.OutputFile;

import java.lang.annotation.Annotation;

public class OutputFilePropertyAnnotationHandler extends AbstractOutputPropertyAnnotationHandler {

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return OutputFile.class;
    }

    @Override
    protected OutputFilePropertyType getFilePropertyType() {
        return OutputFilePropertyType.FILE;
    }
}