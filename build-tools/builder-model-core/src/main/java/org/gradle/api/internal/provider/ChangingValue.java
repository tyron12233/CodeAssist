package org.gradle.api.internal.provider;

import org.gradle.api.Action;

public interface ChangingValue<T> {
    void onValueChange(Action<T> action);
}