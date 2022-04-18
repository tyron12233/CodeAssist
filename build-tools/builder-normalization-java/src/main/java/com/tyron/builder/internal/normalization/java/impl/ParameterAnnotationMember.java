package com.tyron.builder.internal.normalization.java.impl;


public class ParameterAnnotationMember extends AnnotationMember {

    private final int parameter;

    public ParameterAnnotationMember(String name, boolean visible, int parameter) {
        super(name, visible);
        this.parameter = parameter;
    }

    public int getParameter() {
        return parameter;
    }

    @Override
    public int compareTo(AnnotationMember o) {
        return super.compare(o)
                .compare(parameter, ((ParameterAnnotationMember) o).parameter)
                .result();
    }
}