package com.tyron.builder.internal.dispatch;

import com.tyron.builder.internal.UncheckedException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ReflectionDispatch implements Dispatch<MethodInvocation> {
    private final Object target;

    public ReflectionDispatch(Object target) {
        this.target = target;
    }

    @Override
    public void dispatch(MethodInvocation message) {
        try {
            Method method = message.getMethod();
            method.setAccessible(true);
            method.invoke(target, message.getArguments());
        } catch (InvocationTargetException e) {
            throw UncheckedException.throwAsUncheckedException(e.getCause());
        } catch (Throwable throwable) {
            throw UncheckedException.throwAsUncheckedException(throwable);
        }
    }
}