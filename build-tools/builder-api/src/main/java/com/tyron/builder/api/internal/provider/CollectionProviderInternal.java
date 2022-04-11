package com.tyron.builder.api.internal.provider;


public interface CollectionProviderInternal<T, C extends Iterable<T>> extends ProviderInternal<C> {
    Class<? extends T> getElementType();

    int size();
}