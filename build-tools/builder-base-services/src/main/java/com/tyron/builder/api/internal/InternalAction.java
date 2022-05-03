package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.internal.InternalListener;

public interface InternalAction<T> extends Action<T>, InternalListener {
}
