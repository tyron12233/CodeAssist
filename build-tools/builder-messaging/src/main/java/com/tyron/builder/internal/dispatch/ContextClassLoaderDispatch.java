package com.tyron.builder.internal.dispatch;

public class ContextClassLoaderDispatch<T> implements Dispatch<T> {
    private final Dispatch<? super T> dispatch;
    private final ClassLoader contextClassLoader;

    public ContextClassLoaderDispatch(Dispatch<? super T> dispatch, ClassLoader contextClassLoader) {
        this.dispatch = dispatch;
        this.contextClassLoader = contextClassLoader;
    }

    @Override
    public void dispatch(T message) {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(contextClassLoader);
        try {
            dispatch.dispatch(message);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
