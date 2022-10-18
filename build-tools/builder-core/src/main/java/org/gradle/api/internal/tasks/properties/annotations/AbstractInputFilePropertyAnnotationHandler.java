package org.gradle.api.internal.tasks.properties.annotations;

import static org.gradle.api.internal.tasks.properties.ModifierAnnotationCategory.NORMALIZATION;

import org.gradle.internal.fingerprint.DirectorySensitivity;
import org.gradle.internal.fingerprint.LineEndingSensitivity;
import org.gradle.internal.reflect.PropertyMetadata;
import org.gradle.internal.reflect.problems.ValidationProblemId;
import org.gradle.internal.reflect.validation.Severity;
import org.gradle.internal.reflect.validation.TypeValidationContext;
import org.gradle.api.internal.tasks.properties.BeanPropertyContext;
import org.gradle.api.internal.tasks.properties.FileParameterUtils;
import org.gradle.api.internal.tasks.properties.InputFilePropertyType;
import org.gradle.api.internal.tasks.properties.PropertyValue;
import org.gradle.api.internal.tasks.properties.PropertyVisitor;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.ClasspathNormalizer;
import org.gradle.api.tasks.CompileClasspath;
import org.gradle.api.tasks.CompileClasspathNormalizer;
import org.gradle.api.tasks.FileNormalizer;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.work.Incremental;
import org.gradle.work.NormalizeLineEndings;

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