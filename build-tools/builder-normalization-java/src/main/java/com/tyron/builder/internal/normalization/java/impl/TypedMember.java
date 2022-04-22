package com.tyron.builder.internal.normalization.java.impl;

import com.google.common.collect.ComparisonChain;

public abstract class TypedMember extends AnnotatableMember {

    private final String typeDesc;

    public TypedMember(int access, String name, String signature, String typeDesc) {
        super(access, name, signature);
        this.typeDesc = typeDesc;
    }

    public String getTypeDesc() {
        return typeDesc;
    }

    protected ComparisonChain compare(TypedMember o) {
        return super.compare(o)
                .compare(typeDesc == null ? "" : typeDesc, o.typeDesc == null ? "" : o.typeDesc);
    }
}