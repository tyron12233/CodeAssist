package com.tyron.builder.api.internal.tasks.properties.annotations;


import com.tyron.builder.api.internal.tasks.properties.OutputFilePropertyType;
import com.tyron.builder.api.tasks.OutputDirectories;

import java.lang.annotation.Annotation;

public class OutputDirectoriesPropertyAnnotationHandler extends AbstractOutputPropertyAnnotationHandler {

    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return OutputDirectories.class;
    }

    @Override
    protected OutputFilePropertyType getFilePropertyType() {
        return OutputFilePropertyType.DIRECTORIES;
    }
}
