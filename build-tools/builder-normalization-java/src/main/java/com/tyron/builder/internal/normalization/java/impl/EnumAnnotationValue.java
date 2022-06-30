package com.tyron.builder.internal.normalization.java.impl;

public class EnumAnnotationValue extends SimpleAnnotationValue {

    private final String typeDesc;

    public EnumAnnotationValue(String name, String value, String typeDesc) {
        super(name, value);
        this.typeDesc = typeDesc;
    }

    public String getTypeDesc() {
        return typeDesc;
    }
}