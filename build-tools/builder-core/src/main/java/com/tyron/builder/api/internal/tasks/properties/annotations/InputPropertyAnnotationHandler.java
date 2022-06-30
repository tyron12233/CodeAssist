package com.tyron.builder.api.internal.tasks.properties.annotations;

import static com.tyron.builder.internal.reflect.validation.Severity.ERROR;
import static com.tyron.builder.api.internal.tasks.properties.ModifierAnnotationCategory.OPTIONAL;

import com.google.common.collect.ImmutableSet;
import com.tyron.builder.api.file.FileCollection;
import com.tyron.builder.internal.reflect.AnnotationCategory;
import com.tyron.builder.internal.reflect.JavaReflectionUtil;
import com.tyron.builder.internal.reflect.PropertyMetadata;
import com.tyron.builder.internal.reflect.problems.ValidationProblemId;
import com.tyron.builder.internal.reflect.validation.TypeValidationContext;
import com.tyron.builder.api.internal.tasks.properties.BeanPropertyContext;
import com.tyron.builder.api.internal.tasks.properties.PropertyValue;
import com.tyron.builder.api.internal.tasks.properties.PropertyVisitor;
import com.tyron.builder.model.internal.type.ModelType;
import com.tyron.builder.api.tasks.Input;
import com.tyron.builder.api.tasks.Optional;

import java.io.File;
import java.lang.annotation.Annotation;

public class InputPropertyAnnotationHandler implements PropertyAnnotationHandler {
    @Override
    public Class<? extends Annotation> getAnnotationType() {
        return Input.class;
    }

    @Override
    public ImmutableSet<? extends AnnotationCategory> getAllowedModifiers() {
        return ImmutableSet.of(OPTIONAL);
    }

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
        visitor.visitInputProperty(propertyName, value, propertyMetadata.isAnnotationPresent(
                Optional.class));
    }

    @Override
    public void validatePropertyMetadata(PropertyMetadata propertyMetadata, TypeValidationContext validationContext) {
        Class<?> valueType = propertyMetadata.getGetterMethod().getReturnType();
        if (File.class.isAssignableFrom(valueType)
            || java.nio.file.Path.class.isAssignableFrom(valueType)
            || FileCollection.class.isAssignableFrom(valueType)) {
            validationContext.visitPropertyProblem(problem ->
                    problem.withId(ValidationProblemId.INCORRECT_USE_OF_INPUT_ANNOTATION)
                            .forProperty(propertyMetadata.getPropertyName())
                            .reportAs(ERROR)
                            .withDescription(() -> String.format("has @Input annotation used on property of type '%s'", ModelType.of(valueType).getDisplayName()))
                            .happensBecause(() -> "A property of type '" + ModelType.of(valueType).getDisplayName() + "' annotated with @Input cannot determine how to interpret the file")
                            .addPossibleSolution("Annotate with @InputFile for regular files")
                            .addPossibleSolution("Annotate with @InputDirectory for directories")
                            .addPossibleSolution("If you want to track the path, return File.absolutePath as a String and keep @Input")
                            .documentedAt("validation_problems", "incorrect_use_of_input_annotation")
            );
        }
        if (valueType.isPrimitive() && propertyMetadata.isAnnotationPresent(Optional.class)) {
            validationContext.visitPropertyProblem(problem ->
                    problem.withId(ValidationProblemId.CANNOT_USE_OPTIONAL_ON_PRIMITIVE_TYPE)
                            .reportAs(ERROR)
                            .forProperty(propertyMetadata.getPropertyName())
                            .withDescription(() -> "of type " + valueType.getName() + " shouldn't be annotated with @Optional")
                            .happensBecause("Properties of primitive type cannot be optional")
                            .addPossibleSolution("Remove the @Optional annotation")
                            .addPossibleSolution(() -> "Use the " + JavaReflectionUtil.getWrapperTypeForPrimitiveType(valueType).getName() + " type instead")
                            .documentedAt("validation_problems", "cannot_use_optional_on_primitive_types")
            );
        }
    }
}