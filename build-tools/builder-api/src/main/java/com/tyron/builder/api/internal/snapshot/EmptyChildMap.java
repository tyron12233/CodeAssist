package com.tyron.builder.api.internal.snapshot;

import java.util.stream.Stream;

public class EmptyChildMap<T> implements ChildMap<T> {
    private static final EmptyChildMap<Object> INSTANCE = new EmptyChildMap<>();

    @SuppressWarnings("unchecked")
    public static <T> EmptyChildMap<T> getInstance() {
        return (EmptyChildMap<T>) INSTANCE;
    }

    private EmptyChildMap() {
    }

    @Override
    public <R> R withNode(VfsRelativePath targetPath, CaseSensitivity caseSensitivity, NodeHandler<T, R> handler) {
        return handler.handleUnrelatedToAnyChild();
    }

    @Override
    public <RESULT> ChildMap<RESULT> invalidate(VfsRelativePath targetPath, CaseSensitivity caseSensitivity, InvalidationHandler<T, RESULT> handler) {
        handler.handleUnrelatedToAnyChild();
        return getInstance();
    }

    @Override
    public ChildMap<T> store(VfsRelativePath targetPath, CaseSensitivity caseSensitivity, StoreHandler<T> storeHandler) {
        return new SingletonChildMap<>(targetPath.getAsString(), storeHandler.createChild());
    }

    @Override
    public boolean isEmpty() {
        return true;
    }

    @Override
    public Stream<Entry<T>> stream() {
        return Stream.empty();
    }

    @Override
    public String toString() {
        return "";
    }
}