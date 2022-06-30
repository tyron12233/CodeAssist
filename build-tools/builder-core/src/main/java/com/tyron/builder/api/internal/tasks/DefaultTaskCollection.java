package com.tyron.builder.api.internal.tasks;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.NamedDomainObjectCollectionSchema;
import com.tyron.builder.api.Task;
import com.tyron.builder.api.UnknownTaskException;
import com.tyron.builder.api.internal.CollectionCallbackActionDecorator;
import com.tyron.builder.api.internal.DefaultNamedDomainObjectSet;
import com.tyron.builder.api.internal.MutationGuard;
import com.tyron.builder.api.internal.collections.CollectionFilter;
import com.tyron.builder.api.internal.plugins.DslObject;
import com.tyron.builder.api.internal.project.ProjectInternal;
import com.tyron.builder.api.internal.provider.ProviderInternal;
import com.tyron.builder.api.reflect.TypeOf;
import com.tyron.builder.api.tasks.TaskCollection;
import com.tyron.builder.api.tasks.TaskProvider;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.Predicates;

import java.util.Map;
import java.util.function.Predicate;

import static com.tyron.builder.api.reflect.TypeOf.typeOf;

public class DefaultTaskCollection<T extends Task> extends DefaultNamedDomainObjectSet<T> implements TaskCollection<T> {
    private static final Task.Namer NAMER = new Task.Namer();

    protected final ProjectInternal project;

    private final MutationGuard parentMutationGuard;

    public DefaultTaskCollection(Class<T> type, Instantiator instantiator, ProjectInternal project, MutationGuard parentMutationGuard, CollectionCallbackActionDecorator decorator) {
        super(type, instantiator, NAMER, decorator);
        this.project = project;
        this.parentMutationGuard = parentMutationGuard;
    }

    public DefaultTaskCollection(DefaultTaskCollection<? super T> collection, CollectionFilter<T> filter, Instantiator instantiator, ProjectInternal project, MutationGuard parentMutationGuard) {
        super(collection, filter, instantiator, NAMER);
        this.project = project;
        this.parentMutationGuard = parentMutationGuard;
    }

    @Override
    protected <S extends T> DefaultTaskCollection<S> filtered(CollectionFilter<S> filter) {
        return Cast.uncheckedNonnullCast(getInstantiator().newInstance(DefaultTaskCollection.class, this, filter, getInstantiator(), project, parentMutationGuard));
    }

    @Override
    public <S extends T> TaskCollection<S> withType(Class<S> type) {
        return filtered(createFilter(type));
    }

    @Override
    public TaskCollection<T> matching(Predicate<? super T> spec) {
        return filtered(createFilter(spec));
    }

    @Override
    public TaskCollection<T> matching(Closure spec) {
        return matching(Predicates.<T>convertClosureToSpec(spec));
    }

    @Override
    public Action<? super T> whenTaskAdded(Action<? super T> action) {
        return whenObjectAdded(action);
    }

    @Override
    public void whenTaskAdded(Closure closure) {
        whenObjectAdded(closure);
    }

    @Override
    public String getTypeDisplayName() {
        return "task";
    }

    @Override
    protected UnknownTaskException createNotFoundException(String name) {
        return new UnknownTaskException(String.format("Task with name '%s' not found in %s.", name, project));
    }

    @Override
    protected InvalidUserDataException createWrongTypeException(String name, Class expected, Class actual) {
        return new InvalidUserDataException(String.format("The task '%s' (%s) is not a subclass of the given type (%s).", name, actual.getCanonicalName(), expected.getCanonicalName()));
    }

    @Override
    public TaskProvider<T> named(String name) throws UnknownTaskException {
        return (TaskProvider<T>) super.named(name);
    }

    @Override
    public TaskProvider<T> named(String name, Action<? super T> configurationAction) throws UnknownTaskException {
        return (TaskProvider<T>) super.named(name, configurationAction);
    }

    @Override
    public <S extends T> TaskProvider<S> named(String name, Class<S> type) throws UnknownTaskException {
        return (TaskProvider<S>) super.named(name, type);
    }

    @Override
    public <S extends T> TaskProvider<S> named(String name, Class<S> type, Action<? super S> configurationAction) throws UnknownTaskException {
        return (TaskProvider<S>) super.named(name, type, configurationAction);
    }

    @Override
    protected TaskProvider<? extends T> createExistingProvider(String name, T object) {
        // TODO: This isn't quite right. We're leaking the _implementation_ type here.  But for tasks, this is usually right.
        return Cast.uncheckedCast(getInstantiator().newInstance(ExistingTaskProvider.class, this, object.getName(), new DslObject(object).getDeclaredType()));
    }

    @Override
    protected <I extends T> Action<? super I> withMutationDisabled(Action<? super I> action) {
        return parentMutationGuard.withMutationDisabled(super.withMutationDisabled(action));
    }

    @Override
    protected boolean hasWithName(String name) {
//        return (project.getModelRegistry() != null && project.getModelRegistry().state(ModelPath.path("tasks." + name)) != null) || super.hasWithName(name);
        return super.hasWithName(name);
    }

    @Override
    public NamedDomainObjectCollectionSchema getCollectionSchema() {
        return new NamedDomainObjectCollectionSchema() {
            @Override
            public Iterable<? extends NamedDomainObjectSchema> getElements() {
                return Iterables.concat(
                    Iterables.transform(index.asMap().entrySet(), new Function<Map.Entry<String, T>, NamedDomainObjectSchema>() {
                        @Override
                        public NamedDomainObjectSchema apply(final Map.Entry<String, T> e) {
                            return new NamedDomainObjectSchema() {
                                @Override
                                public String getName() {
                                    return e.getKey();
                                }

                                @Override
                                public TypeOf<?> getPublicType() {
                                    // TODO: This returns the wrong public type for domain objects
                                    // created with the eager APIs or added directly to the container.
                                    // This can leak internal types.
                                    // We do not currently keep track of the type used when creating
                                    // a domain object (via create) or the type of the container when
                                    // a domain object is added directly (via add).
                                    return new DslObject(e.getValue()).getPublicType();
                                }
                            };
                        }
                    }),
                    Iterables.transform(index.getPendingAsMap().entrySet(), new Function<Map.Entry<String, ProviderInternal<? extends T>>, NamedDomainObjectSchema>() {
                        @Override
                        public NamedDomainObjectSchema apply(final Map.Entry<String, ProviderInternal<? extends T>> e) {
                            return new NamedDomainObjectSchema() {
                                @Override
                                public String getName() {
                                    return e.getKey();
                                }

                                @Override
                                public TypeOf<?> getPublicType() {
                                    return typeOf(e.getValue().getType());
                                }
                            };
                        }
                    })
                );
            }
        };
    }

    public Action<? super T> whenObjectRemovedInternal(Action<? super T> action) {
        return super.whenObjectRemoved(action);
    }

    // Cannot be private due to reflective instantiation
    public class ExistingTaskProvider<I extends T> extends ExistingNamedDomainObjectProvider<I> implements TaskProvider<I> {
        public ExistingTaskProvider(String name, Class<I> type) {
            super(name, type);
        }

        @Override
        public ValueProducer getProducer() {
            return ValueProducer.taskState(get());
        }
    }
}