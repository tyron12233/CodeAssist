package com.tyron.builder.api.internal;

/**
 * A marker interface for rules which can be safely reused because they are either
 * stateless, or effectively immutable. Ideally this should be inferred, which is
 * why the interface is internal.
 */
public interface ReusableAction {
}
