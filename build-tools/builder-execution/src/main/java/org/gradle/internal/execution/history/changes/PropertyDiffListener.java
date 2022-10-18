package org.gradle.internal.execution.history.changes;

public interface PropertyDiffListener<K, VP, VC> {
    boolean removed(K previousProperty);

    boolean added(K currentProperty);

    boolean updated(K property, VP previous, VC current);
}