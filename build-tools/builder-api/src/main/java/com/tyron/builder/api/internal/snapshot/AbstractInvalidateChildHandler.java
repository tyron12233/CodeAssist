package com.tyron.builder.api.internal.snapshot;

import java.util.Optional;

public abstract class AbstractInvalidateChildHandler<T, RESULT> implements ChildMap.NodeHandler<T, ChildMap<RESULT>> {

    private final ChildMap.InvalidationHandler<T, RESULT> handler;

    public AbstractInvalidateChildHandler(ChildMap.InvalidationHandler<T, RESULT> handler) {
        this.handler = handler;
    }

    public abstract ChildMap<RESULT> getChildMap();

    public abstract ChildMap<RESULT> withReplacedChild(RESULT newChild);

    public abstract ChildMap<RESULT> withReplacedChild(String newChildPath, RESULT newChild);

    public abstract ChildMap<RESULT> withRemovedChild();

    @Override
    public ChildMap<RESULT> handleAsDescendantOfChild(VfsRelativePath pathInChild, T child) {
        Optional<RESULT> invalidatedChild = handler.handleAsDescendantOfChild(pathInChild, child);
        return invalidatedChild
                .map(this::withReplacedChild)
                .orElseGet(this::withRemovedChild);
    }

    @Override
    public ChildMap<RESULT> handleAsAncestorOfChild(String childPath, T child) {
        handler.handleAsAncestorOfChild(childPath, child);
        return withRemovedChild();
    }

    @Override
    public ChildMap<RESULT> handleExactMatchWithChild(T child) {
        handler.handleExactMatchWithChild(child);
        return withRemovedChild();
    }

    @Override
    public ChildMap<RESULT> handleUnrelatedToAnyChild() {
        handler.handleUnrelatedToAnyChild();
        return getChildMap();
    }
}