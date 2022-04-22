package com.tyron.builder.api.internal.tasks.properties.annotations;

import static com.tyron.builder.internal.reflect.problems.ValidationProblemId.INVALID_USE_OF_TYPE_ANNOTATION;
import static com.tyron.builder.internal.reflect.validation.Severity.ERROR;

import com.tyron.builder.internal.reflect.validation.TypeValidationContext;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.stream.Collectors;

public class TypeAnnotationHandlerSupport {

    public static void reportInvalidUseOfTypeAnnotation(Class<?> classWithAnnotationAttached,
                                                        TypeValidationContext visitor,
                                                        Class<? extends Annotation> annotationType,
                                                        Class<?>... appliesOnlyTo) {
        visitor.visitTypeProblem(problem ->
                problem.forType(classWithAnnotationAttached)
                        .reportAs(ERROR)
                        .withId(INVALID_USE_OF_TYPE_ANNOTATION)
                        .withDescription(() -> "is incorrectly annotated with @" + annotationType.getSimpleName())
                        .happensBecause(() -> String.format("This annotation only makes sense on %s types", Arrays.stream(appliesOnlyTo)
                                .map(Class::getSimpleName)
                                .collect(Collectors.joining(", "))
                        ))
                        .documentedAt("validation_problems", "invalid_use_of_cacheable_annotation")
                        .addPossibleSolution("Remove the annotation")
        );
    }
}