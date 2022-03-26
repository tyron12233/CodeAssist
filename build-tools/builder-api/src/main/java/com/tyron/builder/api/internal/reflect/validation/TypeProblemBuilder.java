package com.tyron.builder.api.internal.reflect.validation;


public interface TypeProblemBuilder extends ValidationProblemBuilder<TypeProblemBuilder> {

    TypeProblemBuilder forType(Class<?> type);

}