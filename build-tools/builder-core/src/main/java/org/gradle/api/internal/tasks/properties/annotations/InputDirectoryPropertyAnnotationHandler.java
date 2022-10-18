package org.gradle.api.internal.tasks.properties.annotations;

import static org.gradle.api.internal.tasks.properties.ModifierAnnotationCategory.*;

import com.google.common.collect.ImmutableSet;
import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.reflect.AnnotationCategory;
import org.gradle.internal.reflect.PropertyMetadata;
import org.gradle.api.internal.tasks.properties.InputFilePropertyType;
import org.gradle.api.tasks.InputDirectory;

import java.lang.annotation.Annotation;

public class InputDirectoryPropertyAnnotationHandler extends AbstractInputFilePropertyAnnotationHandler {
    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return InputDirectory.class;
    }

    @Override
    public ImmutableSet<? extends AnnotationCategory> getAllowedModifiers() {
        return ImmutableSet.of(INCREMENTAL, NORMALIZATION, OPTIONAL, IGNORE_EMPTY_DIRECTORIES, NORMALIZE_LINE_ENDINGS);
    }

    @Override
    protected InputFilePropertyType getFilePropertyType() {
        return InputFilePropertyType.DIRECTORY;
    }

    @Override
    protected DirectorySensitivity determineDirectorySensitivity(PropertyMetadata propertyMetadata) {
        // Being an input directory implies ignoring of empty directories.
        return DirectorySensitivity.IGNORE_DIRECTORIES;
    }
}
