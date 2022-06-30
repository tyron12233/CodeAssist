package com.tyron.builder.api.internal.provider;

import com.google.common.collect.Lists;
import com.tyron.builder.api.Action;

import java.util.List;

public class ChangingValueHandler<T> implements ChangingValue<T> {
    private List<Action<T>> handlers;

    @Override
    public void onValueChange(Action<T> action) {
        if (handlers == null) {
            handlers = Lists.newArrayList();
        }
        handlers.add(action);
    }

    public void handle(T previousValue) {
        if (handlers != null) {
            for (Action<T> handler : handlers) {
                handler.execute(previousValue);
            }
        }
    }
}
