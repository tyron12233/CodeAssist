package com.tyron.builder.internal.reflect.validation;


public interface TypeProblemBuilder extends ValidationProblemBuilder<TypeProblemBuilder> {

    TypeProblemBuilder forType(Class<?> type);

}