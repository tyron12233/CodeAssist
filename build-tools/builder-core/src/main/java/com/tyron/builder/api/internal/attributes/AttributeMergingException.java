package com.tyron.builder.api.internal.attributes;

import com.tyron.builder.api.attributes.Attribute;

public class AttributeMergingException extends Exception {
    private final Attribute<?> attribute;
    private final Object leftValue;
    private final Object rightValue;

    public AttributeMergingException(Attribute<?> attribute, Object leftValue, Object rightValue) {
        this.attribute = attribute;
        this.leftValue = leftValue;
        this.rightValue = rightValue;
    }

    public Attribute<?> getAttribute() {
        return attribute;
    }

    public Object getLeftValue() {
        return leftValue;
    }

    public Object getRightValue() {
        return rightValue;
    }
}
