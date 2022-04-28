package org.jetbrains.kotlin.com.intellij.openapi.application;


public class Result<T> {

    protected T myResult;

    public final void setResult(T result) {
        myResult = result;
    }

}
