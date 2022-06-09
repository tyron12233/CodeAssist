package com.tyron.builder.model.internal.core;

public interface ModelViewState {

    void assertCanMutate();

    void assertCanReadChildren();

    void assertCanReadChild(String name);

    boolean isCanMutate();
}
