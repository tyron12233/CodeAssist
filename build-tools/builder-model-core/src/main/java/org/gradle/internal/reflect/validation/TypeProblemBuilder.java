package org.gradle.internal.reflect.validation;


public interface TypeProblemBuilder extends ValidationProblemBuilder<TypeProblemBuilder> {

    TypeProblemBuilder forType(Class<?> type);

}