package com.tyron.builder.api.internal;

import groovy.lang.Closure;
import com.tyron.builder.api.Action;
import com.tyron.builder.api.NamedDomainObjectProvider;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.api.Namer;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Transformers;
import com.tyron.builder.internal.metaobject.AbstractDynamicObject;
import com.tyron.builder.internal.metaobject.ConfigureDelegate;
import com.tyron.builder.internal.metaobject.DynamicInvokeResult;
import com.tyron.builder.internal.metaobject.DynamicObject;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.ConfigureUtil;

import javax.annotation.Nullable;
import java.util.Map;

public abstract class AbstractPolymorphicDomainObjectContainer<T>
        extends AbstractNamedDomainObjectContainer<T> implements PolymorphicDomainObjectContainerInternal<T> {

    private final ContainerElementsDynamicObject elementsDynamicObject = new ContainerElementsDynamicObject();

    protected AbstractPolymorphicDomainObjectContainer(Class<T> type, Instantiator instantiator, Namer<? super T> namer, CollectionCallbackActionDecorator callbackDecorator) {
        super(type, instantiator, namer, callbackDecorator);
    }

    protected abstract <U extends T> U doCreate(String name, Class<U> type);

    @Override
    public <U extends T> U create(String name, Class<U> type) {
        assertMutable("create(String, Class)");
        return create(name, type, null);
    }

    @Override
    public <U extends T> U maybeCreate(String name, Class<U> type) throws InvalidUserDataException {
        T item = findByName(name);
        if (item != null) {
            return Transformers.cast(type).transform(item);
        }
        return create(name, type);
    }

    @Override
    public <U extends T> U create(String name, Class<U> type, Action<? super U> configuration) {
        assertMutable("create(String, Class, Action)");
        assertCanAdd(name);
        U object = doCreate(name, type);
        add(object);
        if (configuration != null) {
            configuration.execute(object);
        }
        return object;
    }

    @Override
    public <U extends T> NamedDomainObjectProvider<U> register(String name, Class<U> type) throws InvalidUserDataException {
        assertMutable("register(String, Class)");
        return createDomainObjectProvider(name, type, null);
    }

    @Override
    public <U extends T> NamedDomainObjectProvider<U> register(String name, Class<U> type, Action<? super U> configurationAction) throws InvalidUserDataException {
        assertMutable("register(String, Class, Action)");
        return createDomainObjectProvider(name, type, configurationAction);
    }

    protected <U extends T> NamedDomainObjectProvider<U> createDomainObjectProvider(String name, Class<U> type, @Nullable Action<? super U> configurationAction) {
        assertCanAdd(name);
        NamedDomainObjectProvider<U> provider = Cast.uncheckedCast(
            getInstantiator().newInstance(NamedDomainObjectCreatingProvider.class, AbstractPolymorphicDomainObjectContainer.this, name, type, configurationAction)
        );
        addLater(provider);
        return provider;
    }

    // Cannot be private due to reflective instantiation
    public class NamedDomainObjectCreatingProvider<I extends T> extends AbstractDomainObjectCreatingProvider<I> {
        public NamedDomainObjectCreatingProvider(String name, Class<I> type, @Nullable Action<? super I> configureAction) {
            super(name, type, configureAction);
        }

        @Override
        protected I createDomainObject() {
            return doCreate(getName(), getType());
        }
    }

    @Override
    protected DynamicObject getElementsAsDynamicObject() {
        return elementsDynamicObject;
    }

    @Override
    protected ConfigureDelegate createConfigureDelegate(Closure configureClosure) {
        return new PolymorphicDomainObjectContainerConfigureDelegate<>(configureClosure, this);
    }

    private class ContainerElementsDynamicObject extends AbstractDynamicObject {
        @Override
        public String getDisplayName() {
            return AbstractPolymorphicDomainObjectContainer.this.getDisplayName();
        }

        @Override
        public boolean hasProperty(String name) {
            return findByName(name) != null;
        }

        @Override
        public DynamicInvokeResult tryGetProperty(String name) {
            Object object = findByName(name);
            return object == null ? DynamicInvokeResult.notFound() : DynamicInvokeResult.found(object);
        }

        @Override
        public Map<String, T> getProperties() {
            return getAsMap();
        }

        @Override
        public boolean hasMethod(String name, Object... arguments) {
            return isConfigureMethod(name, arguments);
        }

        @Override
        public DynamicInvokeResult tryInvokeMethod(String name, Object... arguments) {
            if (isConfigureMethod(name, arguments)) {
                T element = getByName(name);
                Object lastArgument = arguments[arguments.length - 1];
                if (lastArgument instanceof Closure) {
                    ConfigureUtil.configure((Closure) lastArgument, element);
                }
                return DynamicInvokeResult.found(element);
            }
            return DynamicInvokeResult.notFound();
        }

        private boolean isConfigureMethod(String name, Object... arguments) {
            return (arguments.length == 1 && arguments[0] instanceof Closure
                    || arguments.length == 1 && arguments[0] instanceof Class
                    || arguments.length == 2 && arguments[0] instanceof Class && arguments[1] instanceof Closure)
                    && hasProperty(name);
        }
    }

    @Override
    public <U extends T> NamedDomainObjectContainer<U> containerWithType(Class<U> type) {
        return Cast.uncheckedNonnullCast(getInstantiator().newInstance(TypedDomainObjectContainerWrapper.class, type, this));
    }

}
