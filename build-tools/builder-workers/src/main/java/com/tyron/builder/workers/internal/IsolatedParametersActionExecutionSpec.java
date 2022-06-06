package com.tyron.builder.workers.internal;

import com.tyron.builder.internal.isolation.Isolatable;
import com.tyron.builder.workers.WorkAction;
import com.tyron.builder.workers.WorkParameters;

import java.io.File;

public class IsolatedParametersActionExecutionSpec<T extends WorkParameters> {
    private final Class<? extends WorkAction<T>> implementationClass;
    private final String actionImplementationClassName;
    private final Isolatable<T> isolatedParams;
    private final ClassLoaderStructure classLoaderStructure;
    private final File baseDir;
    private final boolean usesInternalServices;
    private final String displayName;

    public IsolatedParametersActionExecutionSpec(Class<? extends WorkAction<T>> implementationClass, String displayName, String actionImplementationClassName, Isolatable<T> isolatedParams, ClassLoaderStructure classLoaderStructure, File baseDir, boolean usesInternalServices) {
        this.implementationClass = implementationClass;
        this.displayName = displayName;
        this.actionImplementationClassName = actionImplementationClassName;
        this.isolatedParams = isolatedParams;
        this.classLoaderStructure = classLoaderStructure;
        this.baseDir = baseDir;
        this.usesInternalServices = usesInternalServices;
    }

    public String getDisplayName() {
        return displayName;
    }

    public File getBaseDir() {
        return baseDir;
    }

    public ClassLoaderStructure getClassLoaderStructure() {
        return classLoaderStructure;
    }

    /**
     * The action to instantiate and execute, possibly a wrapper.
     */
    public Class<? extends WorkAction<T>> getImplementationClass() {
        return implementationClass;
    }

    /**
     * The action that will do the work.
     */
    public String getActionImplementationClassName() {
        return actionImplementationClassName;
    }

    public boolean isInternalServicesRequired() {
        return usesInternalServices;
    }

    public Isolatable<T> getIsolatedParams() {
        return isolatedParams;
    }
}
