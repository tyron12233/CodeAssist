package com.tyron.builder.internal.metaobject;

public class DynamicInvokeResult {

    private static final Object NO_VALUE = new Object();
    private static final DynamicInvokeResult NOT_FOUND = new DynamicInvokeResult(NO_VALUE);
    private static final DynamicInvokeResult NULL = new DynamicInvokeResult(null);

    public static DynamicInvokeResult found(Object value) {
        return value == null ? found() : new DynamicInvokeResult(value);
    }

    public static DynamicInvokeResult found() {
        return DynamicInvokeResult.NULL;
    }

    public static DynamicInvokeResult notFound() {
        return DynamicInvokeResult.NOT_FOUND;
    }

    private final Object value;

    private DynamicInvokeResult(Object value) {
        this.value = value;
    }

    public Object getValue() {
        if (isFound()) {
            return value;
        } else {
            throw new IllegalStateException("Not found");
        }
    }

    public boolean isFound() {
        return value != NO_VALUE;
    }
}