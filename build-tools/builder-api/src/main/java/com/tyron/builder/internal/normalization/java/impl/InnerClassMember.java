package com.tyron.builder.internal.normalization.java.impl;


public class InnerClassMember extends AccessibleMember implements Comparable<InnerClassMember> {

    private final String outerName;
    private final String innerName;

    public InnerClassMember(int access, String name, String outerName, String innerName) {
        super(access, name);
        this.outerName = outerName;
        this.innerName = innerName;
    }

    public String getInnerName() {
        return innerName;
    }

    public String getOuterName() {
        return outerName;
    }

    @Override
    public int compareTo(InnerClassMember o) {
        return super.compare(o)
                .compare(outerName == null ? "" : outerName, o.outerName == null ? "" : o.outerName)
                .compare(innerName == null ? "" : innerName, o.innerName == null ? "" : o.innerName)
                .result();
    }
}