package com.tyron.builder.internal.dispatch;

/**
 * Creates a proxy object which sets the context ClassLoader when invoking methods on the target object.
 *
 * @param <T>
 */
public class ContextClassLoaderProxy<T> {
    private final ProxyDispatchAdapter<T> adapter;

    /**
     * Creates a proxy which dispatches to the given target object.
     */
    public ContextClassLoaderProxy(Class<T> type, T target, ClassLoader contextClassLoader) {
        adapter = new ProxyDispatchAdapter<T>(new ContextClassLoaderDispatch<MethodInvocation>(new ReflectionDispatch(target), contextClassLoader), type);
    }

    public T getSource() {
        return adapter.getSource();
    }
}
