package org.gradle.api.internal.tasks.properties.annotations;

import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.tasks.OutputFile;

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