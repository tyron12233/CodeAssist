package org.gradle.api.internal.tasks.properties.annotations;

import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.tasks.OutputFiles;

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