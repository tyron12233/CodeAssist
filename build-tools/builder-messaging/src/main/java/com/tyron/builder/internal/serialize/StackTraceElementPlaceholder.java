package com.tyron.builder.internal.serialize;

import java.io.Serializable;

public class StackTraceElementPlaceholder implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String classLoaderName;
    private final String moduleName;
    private final String moduleVersion;
    private final String declaringClass;
    private final String methodName;
    private final String fileName;
    private final int lineNumber;

    public StackTraceElementPlaceholder(StackTraceElement ste) {
//        if (false) {
//            classLoaderName = ste.getClassLoaderName();
//            moduleName = ste.getModuleName();
//            moduleVersion = ste.getModuleVersion();
//        } else {
            classLoaderName = null;
            moduleName = null;
            moduleVersion = null;
//        }
        declaringClass = ste.getClassName();
        methodName = ste.getMethodName();
        fileName = ste.getFileName();
        lineNumber = ste.getLineNumber();
    }

    public StackTraceElement toStackTraceElement() {
//        if (false) {
//            return new StackTraceElement(classLoaderName, moduleName, moduleVersion, declaringClass, methodName, fileName, lineNumber);
//        } else {
//
//        }
//
        return new StackTraceElement(declaringClass, methodName, fileName, lineNumber);
    }
}