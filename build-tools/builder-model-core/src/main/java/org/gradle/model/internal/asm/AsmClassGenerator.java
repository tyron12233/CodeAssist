package org.gradle.model.internal.asm;

import org.gradle.internal.classloader.ClassLoaderUtils;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;

public class AsmClassGenerator {

    private final ClassWriter visitor;
    private final String generatedTypeName;
    private final Type generatedType;
    private final Class<?> targetType;

    public AsmClassGenerator(Class<?> targetType, String classNameSuffix) {
        this.targetType = targetType;
        visitor = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        generatedTypeName = targetType.getName() + classNameSuffix;
        generatedType = Type.getType("L" + generatedTypeName.replaceAll("\\.", "/") + ";");
    }

    public ClassWriter getVisitor() {
        return visitor;
    }

    public Class<?> getTargetType() {
        return targetType;
    }

    public String getGeneratedTypeName() {
        return generatedTypeName;
    }

    public Type getGeneratedType() {
        return generatedType;
    }

    public <T> Class<T> define() {
        return define(targetType.getClassLoader());
    }

    public <T> Class<T> define(ClassLoader targetClassLoader) {
        return ClassLoaderUtils.defineDecorator(targetType, targetClassLoader, generatedTypeName, visitor.toByteArray());
    }
}
