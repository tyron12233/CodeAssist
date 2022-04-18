package com.tyron.builder.cache.internal.cacheops;

class CacheOperationStack {
    private int operationCount;

    public boolean isInCacheAction() {
        return operationCount>0;
    }

    public CacheOperationStack pushCacheAction() {
        operationCount++;
        return this;
    }

    public void popCacheAction() {
        checkNotEmpty();
        operationCount--;
    }


    private void checkNotEmpty() {
        if (operationCount==0) {
            throw new IllegalStateException("Operation stack is empty.");
        }
    }

    public boolean isEmpty() {
        return operationCount==0;
    }
}