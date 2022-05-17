package com.tyron.builder.internal.instantiation.generator;

import com.android.dx.DexMaker;
import com.android.dx.TypeId;
import com.tyron.builder.internal.classloader.ClassLoaderUtils;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;

public class DexClassGenerator<T> {

    private final DexMaker visitor;
    private final String generatedTypeName;
    private final TypeId<T> generatedType;
    private final Class<T> targetType;

    public DexClassGenerator(Class<T> targetType, String classNameSuffix) {
        this.targetType = targetType;
        visitor = new DexMaker();
        generatedTypeName = targetType.getName() + classNameSuffix;
        generatedType = TypeId.get("L" + generatedTypeName.replaceAll("\\.", "/") + ";");
    }

    public DexMaker getVisitor() {
        return visitor;
    }

    public Class<?> getTargetType() {
        return targetType;
    }

    public String getGeneratedTypeName() {
        return generatedTypeName;
    }

    public TypeId<T> getGeneratedType() {
        return generatedType;
    }

    public Class<T> define() {
        return define(targetType.getClassLoader());
    }

    public Class<T> define(ClassLoader targetClassLoader) {
        return ClassLoaderUtils
                .defineDecorator(targetType, targetClassLoader, generatedTypeName, visitor.generate());
    }
}
