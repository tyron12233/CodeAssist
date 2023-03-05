package org.gradle.api.internal.tasks.properties.annotations;


import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.tasks.OutputDirectories;

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
