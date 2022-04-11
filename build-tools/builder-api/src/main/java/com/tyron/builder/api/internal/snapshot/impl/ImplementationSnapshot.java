package com.tyron.builder.api.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.tyron.builder.api.internal.hash.ClassLoaderHierarchyHasher;
import com.tyron.builder.api.internal.snapshot.ValueSnapshot;
import com.tyron.builder.api.internal.snapshot.ValueSnapshotter;

import org.jetbrains.annotations.Nullable;

/**
 * Identifies a type in a classloader hierarchy. The type is identified by its name,
 * the classloader hierarchy by its hash code.
 */
public abstract class ImplementationSnapshot implements ValueSnapshot {
    private static final String GENERATED_LAMBDA_CLASS_SUFFIX = "$$Lambda$";
    public enum UnknownReason {
        LAMBDA(
                "was implemented by the Java lambda '%s'.",
                "Using Java lambdas is not supported as task inputs.",
                "Use an (anonymous inner) class instead."),
        UNKNOWN_CLASSLOADER(
                "was loaded with an unknown classloader (class '%s').",
                "Gradle cannot track the implementation for classes loaded with an unknown classloader.",
                "Load your class by using one of Gradle's built-in ways."
        );

        private final String descriptionTemplate;
        private final String reason;
        private final String solution;

        UnknownReason(String descriptionTemplate, String reason, String solution) {
            this.descriptionTemplate = descriptionTemplate;
            this.reason = reason;
            this.solution = solution;
        }

        public String descriptionFor(ImplementationSnapshot implementationSnapshot) {
            return String.format(descriptionTemplate, implementationSnapshot.getTypeName());
        }

        public String getReason() {
            return reason;
        }

        public String getSolution() {
            return solution;
        }
    }

    private final String typeName;

    public static ImplementationSnapshot of(Class<?> type, ClassLoaderHierarchyHasher classLoaderHasher) {
        String className = type.getName();
        return of(className, classLoaderHasher.getClassLoaderHash(type.getClassLoader()), type.isSynthetic() && isLambdaClassName(className));
    }

    public static ImplementationSnapshot of(String className, @Nullable HashCode classLoaderHash) {
        return of(className, classLoaderHash, isLambdaClassName(className));
    }

    private static ImplementationSnapshot of(String typeName, @Nullable HashCode classLoaderHash, boolean lambda) {
        if (classLoaderHash == null) {
            return new UnknownClassloaderImplementationSnapshot(typeName);
        }
        if (lambda) {
            return new LambdaImplementationSnapshot(typeName);
        }
        return new KnownImplementationSnapshot(typeName, classLoaderHash);
    }

    private static boolean isLambdaClassName(String className) {
        return className.contains(GENERATED_LAMBDA_CLASS_SUFFIX);
    }

    protected ImplementationSnapshot(String typeName) {
        this.typeName = typeName;
    }

    public String getTypeName() {
        return typeName;
    }

    @Nullable
    public abstract HashCode getClassLoaderHash();

    public abstract boolean isUnknown();

    @Nullable
    public abstract UnknownReason getUnknownReason();

    @Override
    public ValueSnapshot snapshot(@Nullable Object value, ValueSnapshotter snapshotter) {
        ValueSnapshot other = snapshotter.snapshot(value);
        if (this.isSameSnapshot(other)) {
            return this;
        }
        return other;
    }

    protected abstract boolean isSameSnapshot(@Nullable Object o);
}