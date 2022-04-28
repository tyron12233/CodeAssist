package com.tyron.builder.api.internal.tasks.properties.annotations;

import static com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory.NORMALIZATION;

import com.tyron.builder.internal.fingerprint.DirectorySensitivity;
import com.tyron.builder.internal.fingerprint.LineEndingSensitivity;
import com.tyron.builder.internal.reflect.PropertyMetadata;
import com.tyron.builder.internal.reflect.problems.ValidationProblemId;
import com.tyron.builder.internal.reflect.validation.Severity;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.BeanPropertyContext;
import com.tyron.builder.api.internal.tasks.properties.FileParameterUtils;
import com.tyron.builder.api.internal.tasks.properties.InputFilePropertyType;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.api.tasks.Classpath;
import com.tyron.builder.api.tasks.ClasspathNormalizer;
import com.tyron.builder.api.tasks.CompileClasspath;
import com.tyron.builder.api.tasks.CompileClasspathNormalizer;
import com.tyron.builder.api.tasks.FileNormalizer;
import com.tyron.builder.api.tasks.IgnoreEmptyDirectories;
import com.tyron.builder.api.tasks.Optional;
import com.tyron.builder.api.tasks.PathSensitive;
import com.tyron.builder.api.tasks.PathSensitivity;
import com.tyron.builder.api.tasks.SkipWhenEmpty;
import com.tyron.builder.work.Incremental;
import com.tyron.builder.work.NormalizeLineEndings;

import java.lang.annotation.Annotation;

public abstract class AbstractInputFilePropertyAnnotationHandler implements PropertyAnnotationHandler {
    @Override
    public boolean isPropertyRelevant() {
        return true;
    }

    @Override
    public boolean shouldVisit(PropertyVisitor visitor) {
        return true;
    }

    @Override
    public void visitPropertyValue(String propertyName, PropertyValue value, PropertyMetadata propertyMetadata, PropertyVisitor visitor, BeanPropertyContext context) {
        Annotation fileNormalization = propertyMetadata.getAnnotationForCategory(NORMALIZATION);
        Class<? extends FileNormalizer> fileNormalizer;
        if (fileNormalization == null) {
            fileNormalizer = null;
        } else if (fileNormalization instanceof PathSensitive) {
            PathSensitivity pathSensitivity = ((PathSensitive) fileNormalization).value();
            fileNormalizer = FileParameterUtils.determineNormalizerForPathSensitivity(pathSensitivity);
        } else if (fileNormalization instanceof Classpath) {
            fileNormalizer = ClasspathNormalizer.class;
        } else if (fileNormalization instanceof CompileClasspath) {
            fileNormalizer = CompileClasspathNormalizer.class;
        } else {
            throw new IllegalStateException("Unknown normalization annotation used: " + fileNormalization);
        }
        visitor.visitInputFileProperty(
                propertyName,
                propertyMetadata.isAnnotationPresent(Optional.class),
                propertyMetadata.isAnnotationPresent(SkipWhenEmpty.class),
                determineDirectorySensitivity(propertyMetadata),
                propertyMetadata.isAnnotationPresent(NormalizeLineEndings.class) ? LineEndingSensitivity.NORMALIZE_LINE_ENDINGS : LineEndingSensitivity.DEFAULT,
                propertyMetadata.isAnnotationPresent(Incremental.class),
                fileNormalizer,
                value,
                getFilePropertyType()
        );
    }

    @SuppressWarnings("deprecation")
    protected DirectorySensitivity determineDirectorySensitivity(PropertyMetadata propertyMetadata) {
        return propertyMetadata.isAnnotationPresent(IgnoreEmptyDirectories.class)
                ? DirectorySensitivity.IGNORE_DIRECTORIES
                : DirectorySensitivity.UNSPECIFIED;
    }

    @Override
    public void validatePropertyMetadata(PropertyMetadata propertyMetadata, TypeValidationContext validationContext) {
        if (!propertyMetadata.hasAnnotationForCategory(NORMALIZATION)) {
            validationContext.visitPropertyProblem(problem -> {
                String propertyName = propertyMetadata.getPropertyName();
                problem.withId(ValidationProblemId.MISSING_NORMALIZATION_ANNOTATION)
                        .reportAs(Severity.ERROR)
                        .onlyAffectsCacheableWork()
                        .forProperty(propertyName)
                        .withDescription(() -> String.format("is annotated with @%s but missing a normalization strategy", getAnnotationType().getSimpleName()))
                        .happensBecause("If you don't declare the normalization, outputs can't be re-used between machines or locations on the same machine, therefore caching efficiency drops significantly")
                        .addPossibleSolution("Declare the normalization strategy by annotating the property with either @PathSensitive, @Classpath or @CompileClasspath")
                        .documentedAt("validation_problems", "missing_normalization_annotation");
            });
        }
    }

    protected abstract InputFilePropertyType getFilePropertyType();
}