package com.tyron.builder.cache.internal.cacheops;

public class CacheAccessOperationsStack {
    private final ThreadLocal<CacheOperationStack> stackForThread = new ThreadLocal<CacheOperationStack>();

    public void pushCacheAction() {
        CacheOperationStack stack = getOrCreateStack();
        stack.pushCacheAction();
    }

    public void popCacheAction() {
        CacheOperationStack stack = stackForThread.get();
        if (stack == null) {
            throw new IllegalStateException("Operation stack is empty.");
        }
        stack.popCacheAction();
        if (stack.isEmpty()) {
            stackForThread.remove();
        }
    }

    public boolean isInCacheAction() {
        CacheOperationStack stack = stackForThread.get();
        return stack != null && stack.isInCacheAction();
    }

    private CacheOperationStack getOrCreateStack() {
        CacheOperationStack stack = stackForThread.get();
        if (stack == null) {
            stack = new CacheOperationStack();
            stackForThread.set(stack);
        }
        return stack;
    }
}