package com.tyron.builder.internal.normalization.java.impl;

import java.util.Arrays;

public class ClassMember extends AnnotatableMember {

    private final int version;
    private final String superName;
    private final String[] interfaces;

    public ClassMember(int version, int access, String name, String signature, String superName, String[] interfaces) {
        super(access, name, signature);
        this.version = version;
        this.superName = superName;
        this.interfaces = interfaces;
    }

    public String[] getInterfaces() {
        return Arrays.copyOf(interfaces, interfaces.length);
    }

    public String getSuperName() {
        return superName;
    }

    public int getVersion() {
        return version;
    }
}