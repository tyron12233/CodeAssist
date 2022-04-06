package com.tyron.builder.internal.normalization.java.impl;

import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.util.SortedSet;

public abstract class AnnotatableMember extends AccessibleMember {

    private final SortedSet<AnnotationMember> annotations = Sets.newTreeSet();
    private final String signature;

    public AnnotatableMember(int access, String name, String signature) {
        super(access, name);
        this.signature = signature;
    }

    public SortedSet<AnnotationMember> getAnnotations() {
        return ImmutableSortedSet.copyOf(annotations);
    }

    public void addAnnotation(AnnotationMember annotationMember) {
        annotations.add(annotationMember);
    }

    public String getSignature() {
        return signature;
    }

    protected ComparisonChain compare(AnnotatableMember o) {
        return super.compare(o)
                .compare(signature == null ? "" : signature, o.signature == null ? "" : o.signature);
    }
}