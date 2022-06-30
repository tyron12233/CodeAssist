package com.tyron.builder.api.internal.tasks.properties.annotations;

import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertyType;
import com.tyron.builder.api.tasks.OutputDirectory;

import java.lang.annotation.Annotation;

public class OutputDirectoryPropertyAnnotationHandler extends AbstractOutputPropertyAnnotationHandler {

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return OutputDirectory.class;
    }

    @Override
    protected OutputFilePropertyType getFilePropertyType() {
        return OutputFilePropertyType.DIRECTORY;
    }
}