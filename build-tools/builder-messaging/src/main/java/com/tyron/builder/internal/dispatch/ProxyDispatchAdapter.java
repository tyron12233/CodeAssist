package com.tyron.builder.internal.dispatch;


import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Adapts from interface T to a {@link Dispatch}
 *
 * @param <T>
 */
public class ProxyDispatchAdapter<T> {
    private final Class<T> type;
    private final T source;

    public ProxyDispatchAdapter(Dispatch<? super MethodInvocation> dispatch, Class<T> type, Class<?>... extraTypes) {
        this.type = type;
        List<Class<?>> types = new ArrayList<Class<?>>();
        ClassLoader classLoader = type.getClassLoader();
        types.add(type);
        for (Class<?> extraType : extraTypes) {
            ClassLoader candidate = extraType.getClassLoader();
            if (candidate != classLoader && candidate != null) {
                try {
                    if (candidate.loadClass(type.getName()) != null) {
                        classLoader = candidate;
                    }
                } catch (ClassNotFoundException e) {
                    // Ignore
                }
            }
            types.add(extraType);
        }
        source = type.cast(Proxy.newProxyInstance(classLoader, types.toArray(new Class<?>[0]),
                new DispatchingInvocationHandler(type, dispatch)));
    }

    public Class<T> getType() {
        return type;
    }

    public T getSource() {
        return source;
    }

    private static class DispatchingInvocationHandler implements InvocationHandler {
        private final Class<?> type;
        private final Dispatch<? super MethodInvocation> dispatch;

        private DispatchingInvocationHandler(Class<?> type, Dispatch<? super MethodInvocation> dispatch) {
            this.type = type;
            this.dispatch = dispatch;
        }

        @Override
        public Object invoke(Object target, Method method, Object[] parameters) throws Throwable {
            if (method.getName().equals("equals")) {
                Object parameter = parameters[0];
                if (parameter == null || !Proxy.isProxyClass(parameter.getClass())) {
                    return false;
                }
                Object handler = Proxy.getInvocationHandler(parameter);
                if (!DispatchingInvocationHandler.class.isInstance(handler)) {
                    return false;
                }

                DispatchingInvocationHandler otherHandler = (DispatchingInvocationHandler) handler;
                return otherHandler.type.equals(type) && otherHandler.dispatch == dispatch;
            }

            if (method.getName().equals("hashCode")) {
                return dispatch.hashCode();
            }
            if (method.getName().equals("toString")) {
                return type.getSimpleName() + " broadcast";
            }
            dispatch.dispatch(new MethodInvocation(method, parameters));
            return null;
        }
    }
}