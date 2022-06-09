package com.tyron.builder.api.internal;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.InvalidUserDataException;
import com.tyron.builder.api.Namer;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.util.internal.NameValidator;

/**
 * A {@link AbstractNamedDomainObjectContainer} that performs name validation before creating a new domain object.
 *
 * @see NameValidator#validate(String, String, String)
 */
public abstract class AbstractValidatingNamedDomainObjectContainer<T> extends AbstractNamedDomainObjectContainer<T> {

    private final String nameDescription;

    protected AbstractValidatingNamedDomainObjectContainer(Class<T> type, Instantiator instantiator, Namer<? super T> namer, CollectionCallbackActionDecorator callbackActionDecorator) {
        super(type, instantiator, namer, callbackActionDecorator);
        nameDescription = type.getSimpleName() + " name";
    }

    protected AbstractValidatingNamedDomainObjectContainer(Class<T> type, Instantiator instantiator, CollectionCallbackActionDecorator callbackActionDecorator) {
        super(type, instantiator, callbackActionDecorator);
        nameDescription = type.getSimpleName() + " name";
    }

    @Override
    public T create(String name, Action<? super T> configureAction) throws InvalidUserDataException {
        NameValidator.validate(name, nameDescription, "");
        return super.create(name, configureAction);
    }
}
