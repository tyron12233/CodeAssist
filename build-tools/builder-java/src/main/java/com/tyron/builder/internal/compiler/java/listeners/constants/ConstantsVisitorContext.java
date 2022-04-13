package com.tyron.builder.internal.compiler.java.listeners.constants;

import java.util.function.BiConsumer;

class ConstantsVisitorContext {

    private final String visitedClass;
    private final BiConsumer<String, String> consumer;

    public ConstantsVisitorContext(String visitedClass, BiConsumer<String, String> consumer) {
        this.visitedClass = visitedClass;
        this.consumer = consumer;
    }

    public String getVisitedClass() {
        return visitedClass;
    }

    public void addConstantOrigin(String constantOrigin) {
        if (!constantOrigin.equals(visitedClass)) {
            consumer.accept(constantOrigin, visitedClass);
        }
    }

}

