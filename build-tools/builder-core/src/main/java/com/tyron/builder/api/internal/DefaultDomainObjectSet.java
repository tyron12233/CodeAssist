package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.internal.collections.CollectionEventRegister;
import com.tyron.builder.api.internal.collections.CollectionFilter;
import com.tyron.builder.api.internal.collections.ElementSource;
import com.tyron.builder.api.internal.collections.IterationOrderRetainingSetElementSource;
import com.tyron.builder.internal.ImmutableActionSet;
import com.tyron.builder.util.Predicates;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

import groovy.lang.Closure;

public class DefaultDomainObjectSet<T> extends DefaultDomainObjectCollection<T> implements DomainObjectSet<T> {
    // TODO: Combine these with MutationGuard
    private ImmutableActionSet<Void> beforeContainerChange = ImmutableActionSet.empty();

    /**
     * This internal constructor is used by the 'com.android.application' plugin which we test as part of our ci pipeline.
     */
    @Deprecated
    public DefaultDomainObjectSet(Class<? extends T> type) {
        super(type, new IterationOrderRetainingSetElementSource<T>(), CollectionCallbackActionDecorator.NOOP);
//        DeprecationLogger.deprecateInternalApi("constructor DefaultDomainObjectSet(Class<T>)")
//            .replaceWith("ObjectFactory.domainObjectSet(Class<T>)")
//            .willBeRemovedInGradle8()
//            .withUserManual("custom_gradle_types", "domainobjectset")
//            .nagUser();
    }

    public DefaultDomainObjectSet(Class<? extends T> type, CollectionCallbackActionDecorator decorator) {
        super(type, new IterationOrderRetainingSetElementSource<T>(), decorator);
    }

    /**
     * Adds an action which is executed before this collection is mutated with the addition or removal of elements.
     * Any exception thrown by the action will veto the mutation.
     *
     * TODO: Combine this with the MutationGuard or rework CompositeDomainObject to behave with MutationGuard/MutationValidator.
     * The mutation validators used in DefaultConfiguration only expect to be used with add/remove methods and fail when we
     * correctly try to also prevent all/withType/etc mutation methods.
     *
     * assertMutableCollectionContents is only used by add/remove methods, but we should remove this special handling and fix
     * DefaultConfiguration and CompositeDomainObjects.
     */
    public void beforeCollectionChanges(Action<Void> action) {
        beforeContainerChange = beforeContainerChange.add(action);
    }

    @Override
    protected void assertMutableCollectionContents() {
        beforeContainerChange.execute(null);
    }

    public DefaultDomainObjectSet(Class<? extends T> type, ElementSource<T> store, CollectionCallbackActionDecorator decorator) {
        super(type, store, decorator);
    }

    protected DefaultDomainObjectSet(DefaultDomainObjectSet<? super T> store, CollectionFilter<T> filter) {
        this(filter.getType(), store.filteredStore(filter), store.filteredEvents(filter));
    }

    protected DefaultDomainObjectSet(Class<? extends T> type, ElementSource<T> store, CollectionEventRegister<T> eventRegister) {
        super(type, store, eventRegister);
    }

    @Override
    protected <S extends T> DefaultDomainObjectSet<S> filtered(CollectionFilter<S> filter) {
        return new DefaultDomainObjectSet<S>(this, filter);
    }

    @Override
    public <S extends T> DomainObjectSet<S> withType(Class<S> type) {
        return filtered(createFilter(type));
    }

    @Override
    public DomainObjectSet<T> matching(Predicate<? super T> spec) {
        return filtered(createFilter(spec));
    }

    @Override
    public DomainObjectSet<T> matching(Closure spec) {
        return matching(Predicates.<T>convertClosureToSpec(spec));
    }

    @Override
    public Set<T> findAll(Closure cl) {
        return findAll(cl, new LinkedHashSet<T>());
    }
}
