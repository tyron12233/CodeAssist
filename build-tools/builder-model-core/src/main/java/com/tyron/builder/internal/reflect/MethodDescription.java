package com.tyron.builder.internal.reflect;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.tyron.builder.internal.Cast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;

public class MethodDescription {

    private String owner;
    private String name;
    private String returnType;
    private Iterable<String> parameterTypes;

    public MethodDescription(String name) {
        this.name = name;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (returnType != null) {
            sb.append(returnType).append(" ");
        }
        if (owner != null) {
            sb.append(owner).append("#");
        }
        sb.append(name);

        if (parameterTypes != null) {
            sb.append("(");
            Joiner.on(", ").appendTo(sb, parameterTypes);
            sb.append(")");
        }

        return sb.toString();
    }

    public static MethodDescription name(String name) {
        return new MethodDescription(name);
    }

    public static MethodDescription of(Method method) {
        return name(method.getName())
                .owner(method.getDeclaringClass())
                .returns(method.getGenericReturnType())
                .takes(method.getGenericParameterTypes());
    }

    public static MethodDescription of(Constructor<?> constructor) {
        return name("<init>")
                .owner(constructor.getDeclaringClass())
                .takes(constructor.getGenericParameterTypes());
    }

    private String typeName(Type type) {
        if (type == null) {
            return null;
        }
        return type instanceof Class ? Cast.cast(Class.class, type).getName() : type.toString();
    }

    public MethodDescription returns(Type returnType) {
        this.returnType = typeName(returnType);
        return this;
    }

    public MethodDescription owner(Class<?> owner) {
        this.owner = typeName(owner);
        return this;
    }

    public MethodDescription takes(Type[] parameterTypes) {
        this.parameterTypes = Iterables.transform(Arrays.asList(parameterTypes), new Function<Type, String>() {
            @Override
            public String apply(Type input) {
                return typeName(input);
            }
        });
        return this;
    }

}
