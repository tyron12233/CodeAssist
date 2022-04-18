package com.tyron.builder.internal.normalization.java.impl;

import com.google.common.collect.Ordering;
import org.objectweb.asm.Type;

import java.lang.reflect.Modifier;

public class FieldMember extends TypedMember implements Comparable<FieldMember> {

    private final Object value;

    public FieldMember(int access, String name, String signature, String typeDesc, Object value) {
        super(access, name, signature, typeDesc);
        this.value = value;
    }

    @Override
    public int compareTo(FieldMember o) {
        return super.compare(o).compare(value, o.value, Ordering.arbitrary()).result();
    }

    @Override
    public String toString() {
        return String.format(
                "%s %s %s", Modifier.toString(getAccess()), Type.getType(getTypeDesc()).getClassName(), getName());
    }

    public Object getValue() {
        return value;
    }
}