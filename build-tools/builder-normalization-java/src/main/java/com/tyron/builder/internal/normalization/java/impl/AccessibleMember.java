package com.tyron.builder.internal.normalization.java.impl;


import com.google.common.collect.ComparisonChain;

public abstract class AccessibleMember extends Member {

    private final int access;

    public AccessibleMember(int access, String name) {
        super(name);
        this.access = access;
    }

    public int getAccess() {
        return access;
    }

    protected ComparisonChain compare(AccessibleMember o) {
        return super.compare(o).compare(access, o.access);
    }
}