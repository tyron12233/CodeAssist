package com.tyron.builder.api.internal.tasks.compile.processing;

import com.tyron.builder.api.internal.tasks.compile.incremental.processing.IncrementalAnnotationProcessorType;

import java.io.Serializable;

/**
 * Information about an annotation processor, based on its static metadata
 * in <code>META-INF/services/javax.annotation.processing.Processor</code> and
 * <code>META-INF/gradle/incremental.annotation.processors</code>
 */
public class AnnotationProcessorDeclaration implements Serializable {
    private final String className;
    private final IncrementalAnnotationProcessorType type;

    public AnnotationProcessorDeclaration(String className, IncrementalAnnotationProcessorType type) {
        this.className = className;
        this.type = type;
    }

    public String getClassName() {
        return className;
    }

    public IncrementalAnnotationProcessorType getType() {
        return type;
    }

    @Override
    public String toString() {
        return className + " (type: " + type + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        AnnotationProcessorDeclaration that = (AnnotationProcessorDeclaration) o;

        if (!className.equals(that.className)) {
            return false;
        }
        return type == that.type;
    }

    @Override
    public int hashCode() {
        int result = className.hashCode();
        result = 31 * result + type.hashCode();
        return result;
    }
}
