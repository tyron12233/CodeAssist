package com.tyron.builder.internal.metaobject;

/**
 * Provides dynamic access to methods of some object.
 */
public interface MethodAccess {
    /**
     * Returns true when this object is known to have a method with the given name that accepts the given arguments.
     *
     * <p>Note that not every method is known. Some methods may require an attempt invoke it in order for them to be discovered.</p>
     */
    boolean hasMethod(String name, Object... arguments);

    /**
     * Invokes the method with the given name and arguments.
     */
    DynamicInvokeResult tryInvokeMethod(String name, Object... arguments);
}
