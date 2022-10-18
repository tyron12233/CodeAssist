package org.gradle.api.internal.collections;

import org.gradle.api.Action;
import org.gradle.api.internal.CollectionCallbackActionDecorator;
import org.gradle.internal.ImmutableActionSet;

import javax.annotation.Nullable;

public interface CollectionEventRegister<T> {
    boolean isSubscribed(@Nullable Class<?> type);

    /**
     * Returns a snapshot of the <em>current</em> set of actions to run when an element is added.
     */
    ImmutableActionSet<T> getAddActions();

    void fireObjectAdded(T element);

    void fireObjectRemoved(T element);

    Action<? super T> registerEagerAddAction(Class<? extends T> type, Action<? super T> addAction);

    Action<? super T> registerLazyAddAction(Action<? super T> addAction);

    void registerRemoveAction(Class<? extends T> type, Action<? super T> removeAction);

    <S extends T> CollectionEventRegister<S> filtered(CollectionFilter<S> filter);

    // TODO: Migrate this away from here
    CollectionCallbackActionDecorator getDecorator();
}
