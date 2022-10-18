package org.gradle.api.internal.tasks.properties.annotations;

import org.gradle.api.internal.tasks.properties.OutputFilePropertyType;
import org.gradle.api.tasks.OutputDirectory;

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