package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.Action;

public interface ChangingValue<T> {
    void onValueChange(Action<T> action);
}