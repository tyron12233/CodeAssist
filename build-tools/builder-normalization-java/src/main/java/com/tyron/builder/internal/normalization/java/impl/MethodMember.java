package com.tyron.builder.internal.normalization.java.impl;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.objectweb.asm.Type;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.SortedSet;

public class MethodMember extends TypedMember implements Comparable<MethodMember> {
    private static final Ordering<Iterable<String>> LEXICOGRAPHICAL_ORDERING = Ordering.<String>natural().lexicographical();
    private final SortedSet<String> exceptions = Sets.newTreeSet();
    private final SortedSet<AnnotationMember> parameterAnnotations = Sets.newTreeSet();

    public MethodMember(int access, String name, String typeDesc, String signature, String[] exceptions) {
        super(access, name, signature, typeDesc);
        if (exceptions != null && exceptions.length > 0) {
            this.exceptions.addAll(Arrays.asList(exceptions));
        }
    }

    public SortedSet<String> getExceptions() {
        return ImmutableSortedSet.copyOf(exceptions);
    }

    public SortedSet<AnnotationMember> getParameterAnnotations() {
        return ImmutableSortedSet.copyOf(parameterAnnotations);
    }

    public void addParameterAnnotation(ParameterAnnotationMember parameterAnnotationMember) {
        parameterAnnotations.add(parameterAnnotationMember);
    }

    @Override
    public int compareTo(MethodMember o) {
        return super.compare(o)
                .compare(exceptions, o.exceptions, LEXICOGRAPHICAL_ORDERING)
                .result();
    }

    @Override
    public String toString() {
        StringBuilder methodDesc = new StringBuilder();
        methodDesc.append(Modifier.toString(getAccess())).append(" ");
        methodDesc.append(Type.getReturnType(getTypeDesc()).getClassName()).append(" ");
        methodDesc.append(getName());
        methodDesc.append("(");
        Type[] argumentTypes = Type.getArgumentTypes(getTypeDesc());
        for (int i = 0, argumentTypesLength = argumentTypes.length; i < argumentTypesLength; i++) {
            Type type = argumentTypes[i];
            methodDesc.append(type.getClassName());
            if (i < argumentTypesLength - 1) {
                methodDesc.append(", ");
            }
        }
        methodDesc.append(")");
        return methodDesc.toString();
    }
}