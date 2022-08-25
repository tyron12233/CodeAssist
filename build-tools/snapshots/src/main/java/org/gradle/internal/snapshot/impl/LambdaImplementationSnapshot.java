package org.gradle.internal.snapshot.impl;

import com.google.common.hash.HashCode;
import com.google.common.hash.Hasher;

import org.gradle.internal.hash.Hashes;

import javax.annotation.Nullable;
import java.lang.invoke.SerializedLambda;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class LambdaImplementationSnapshot extends ImplementationSnapshot {

    private final HashCode classLoaderHash;

    private final String functionalInterfaceClass;
    private final String implClass;
    private final String implMethodName;
    private final String implMethodSignature;
    private final int implMethodKind;

    public LambdaImplementationSnapshot(HashCode classLoaderHash, SerializedLambda lambda) {
        this(
                lambda.getCapturingClass(),
                classLoaderHash,
                lambda.getFunctionalInterfaceClass(),
                lambda.getImplClass(),
                lambda.getImplMethodName(),
                lambda.getImplMethodSignature(),
                lambda.getImplMethodKind()
        );
    }

    public LambdaImplementationSnapshot(
            String capturingClass,
            HashCode classLoaderHash,
            String functionalInterfaceClass,
            String implClass,
            String implMethodName,
            String implMethodSignature,
            int implMethodKind
    ) {
        super(capturingClass);
        this.classLoaderHash = classLoaderHash;
        this.functionalInterfaceClass = functionalInterfaceClass;
        this.implClass = implClass;
        this.implMethodName = implMethodName;
        this.implMethodSignature = implMethodSignature;
        this.implMethodKind = implMethodKind;
    }

    @Override
    public void appendToHasher(Hasher hasher) {
        hasher.putString(LambdaImplementationSnapshot.class.getName(), StandardCharsets.UTF_8);
        hasher.putString(classIdentifier, StandardCharsets.UTF_8);
        Hashes.putHash(hasher, classLoaderHash);
        hasher.putString(functionalInterfaceClass, StandardCharsets.UTF_8);
        hasher.putString(implClass, StandardCharsets.UTF_8);
        hasher.putString(implMethodName, StandardCharsets.UTF_8);
        hasher.putString(implMethodSignature, StandardCharsets.UTF_8);
        hasher.putInt(implMethodKind);
    }

    @Nullable
    @Override
    public HashCode getClassLoaderHash() {
        return classLoaderHash;
    }

    public String getFunctionalInterfaceClass() {
        return functionalInterfaceClass;
    }

    public String getImplClass() {
        return implClass;
    }

    public String getImplMethodName() {
        return implMethodName;
    }

    public String getImplMethodSignature() {
        return implMethodSignature;
    }

    public int getImplMethodKind() {
        return implMethodKind;
    }

    @Override
    protected boolean isSameSnapshot(@Nullable Object o) {
        return equals(o);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        LambdaImplementationSnapshot that = (LambdaImplementationSnapshot) o;
        return classIdentifier.equals(that.classIdentifier) &&
               classLoaderHash.equals(that.classLoaderHash) &&
               functionalInterfaceClass.equals(that.functionalInterfaceClass) &&
               implClass.equals(that.implClass) &&
               implMethodName.equals(that.implMethodName) &&
               implMethodSignature.equals(that.implMethodSignature) &&
               implMethodKind == that.implMethodKind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(classIdentifier, classLoaderHash, functionalInterfaceClass, implClass, implMethodName, implMethodSignature, implMethodKind);
    }

    @Override
    public String toString() {
        return classIdentifier + "::" + implMethodName + "@" + classLoaderHash;
    }
}