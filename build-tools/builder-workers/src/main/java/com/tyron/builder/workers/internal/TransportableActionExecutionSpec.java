package com.tyron.builder.workers.internal;

import java.io.File;

public class TransportableActionExecutionSpec {
    protected final String implementationClassName;
    private final byte[] serializedParameters;
    private final ClassLoaderStructure classLoaderStructure;
    private final File baseDir;
    private final boolean usesInternalServices;

    public TransportableActionExecutionSpec(String implementationClassName, byte[] serializedParameters, ClassLoaderStructure classLoaderStructure, File baseDir, boolean usesInternalServices) {
        this.implementationClassName = implementationClassName;
        this.serializedParameters = serializedParameters;
        this.classLoaderStructure = classLoaderStructure;
        this.baseDir = baseDir;
        this.usesInternalServices = usesInternalServices;
    }

    public File getBaseDir() {
        return baseDir;
    }

    public boolean isInternalServicesRequired() {
        return usesInternalServices;
    }

    public ClassLoaderStructure getClassLoaderStructure() {
        return classLoaderStructure;
    }

    public String getImplementationClassName() {
        return implementationClassName;
    }

    public byte[] getSerializedParameters() {
        return serializedParameters;
    }
}