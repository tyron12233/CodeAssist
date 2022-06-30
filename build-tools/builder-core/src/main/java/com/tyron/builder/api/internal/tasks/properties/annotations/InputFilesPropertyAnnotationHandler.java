package com.tyron.builder.api.internal.tasks.properties.annotations;



import static com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory.*;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.internal.reflect.AnnotationCategory;
import com.tyron.builder.api.internal.tasks.properties.InputFilePropertyType;
import com.tyron.builder.api.tasks.InputFiles;

import java.lang.annotation.Annotation;

public class InputFilesPropertyAnnotationHandler extends AbstractInputFilePropertyAnnotationHandler {
    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return InputFiles.class;
    }

    @Override
    public ImmutableSet<? extends AnnotationCategory> getAllowedModifiers() {
        return ImmutableSet.of(INCREMENTAL, NORMALIZATION, OPTIONAL, IGNORE_EMPTY_DIRECTORIES, NORMALIZE_LINE_ENDINGS);
    }

    @Override
    protected InputFilePropertyType getFilePropertyType() {
        return InputFilePropertyType.FILES;
    }
}