package org.gradle.api.internal;

import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Namer;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.util.internal.NameValidator;

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
