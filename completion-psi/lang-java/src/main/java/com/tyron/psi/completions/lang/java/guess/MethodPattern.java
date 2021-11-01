package com.tyron.psi.completions.lang.java.guess;

import org.jetbrains.annotations.NonNls;

class MethodPattern{
    public final @NonNls String methodName;
    public final int parameterCount;
    public final int parameterIndex; // -1 for return type

    MethodPattern(@NonNls String methodName, int parameterCount, int parameterIndex) {
        this.methodName = methodName;
        this.parameterCount = parameterCount;
        this.parameterIndex = parameterIndex;
    }
}