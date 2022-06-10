package org.gradle.api.internal;

import org.gradle.api.Action;
import org.gradle.internal.InternalListener;

public interface InternalAction<T> extends Action<T>, InternalListener {
}
