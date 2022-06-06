package com.tyron.builder.util.internal;

import groovy.lang.Closure;
import org.codehaus.groovy.runtime.GeneratedClosure;
import com.tyron.builder.api.Action;
import com.tyron.builder.internal.metaobject.DynamicObjectUtil;
import com.tyron.builder.internal.Actions;
import com.tyron.builder.internal.metaobject.ConfigureDelegate;
import com.tyron.builder.internal.metaobject.DynamicInvokeResult;
import com.tyron.builder.internal.metaobject.DynamicObject;
import com.tyron.builder.util.Configurable;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;

import static com.tyron.builder.util.internal.CollectionUtils.toStringList;

public class ConfigureUtil {

    public static <T> T configureByMap(Map<?, ?> properties, T delegate) {
        if (properties.isEmpty()) {
            return delegate;
        }
        DynamicObject dynamicObject = DynamicObjectUtil.asDynamicObject(delegate);

        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String name = entry.getKey().toString();
            Object value = entry.getValue();

            DynamicInvokeResult result = dynamicObject.trySetProperty(name, value);
            if (result.isFound()) {
                continue;
            }

            result = dynamicObject.tryInvokeMethod(name, value);
            if (!result.isFound()) {
                throw dynamicObject.setMissingProperty(name);
            }
        }

        return delegate;
    }

    public static <T> T configureByMap(Map<?, ?> properties, T delegate, Collection<?> mandatoryKeys) {
        if (!mandatoryKeys.isEmpty()) {
            Collection<String> missingKeys = toStringList(mandatoryKeys);
            missingKeys.removeAll(toStringList(properties.keySet()));
            if (!missingKeys.isEmpty()) {
                throw new IncompleteInputException("Input configuration map does not contain following mandatory keys: " + missingKeys, missingKeys);
            }
        }
        return configureByMap(properties, delegate);
    }

    public static class IncompleteInputException extends RuntimeException {
        private final Collection missingKeys;

        public IncompleteInputException(String message, Collection missingKeys) {
            super(message);
            this.missingKeys = missingKeys;
        }

        public Collection getMissingKeys() {
            return missingKeys;
        }
    }

    /**
     * <p>Configures {@code target} with {@code configureClosure}, via the {@link Configurable} interface if necessary.</p>
     *
     * <p>If {@code target} does not implement {@link Configurable} interface, it is set as the delegate of a clone of
     * {@code configureClosure} with a resolve strategy of {@code DELEGATE_FIRST}.</p>
     *
     * <p>If {@code target} does implement the {@link Configurable} interface, the {@code configureClosure} will be passed to
     * {@code delegate}'s {@link Configurable#configure(Closure)} method.</p>
     *
     * @param configureClosure The configuration closure
     * @param target The object to be configured
     * @return The delegate param
     */
    public static <T> T configure(@Nullable Closure configureClosure, T target) {
        if (configureClosure == null) {
            return target;
        }

        if (target instanceof Configurable) {
            ((Configurable) target).configure(configureClosure);
        } else {
            configureTarget(configureClosure, target, new ConfigureDelegate(configureClosure, target));
        }

        return target;
    }

    /**
     * Creates an action that uses the given closure to configure objects of type T.
     */
    public static <T> Action<T> configureUsing(@Nullable final Closure configureClosure) {
        if (configureClosure == null) {
            return Actions.doNothing();
        }

        return new WrappedConfigureAction<T>(configureClosure);
    }

    /**
     * Called from an object's {@link Configurable#configure} method.
     */
    public static <T> T configureSelf(@Nullable Closure configureClosure, T target) {
        if (configureClosure == null) {
            return target;
        }

        configureTarget(configureClosure, target, new ConfigureDelegate(configureClosure, target));
        return target;
    }

    /**
     * Called from an object's {@link Configurable#configure} method.
     */
    public static <T> T configureSelf(@Nullable Closure configureClosure, T target, ConfigureDelegate closureDelegate) {
        if (configureClosure == null) {
            return target;
        }

        configureTarget(configureClosure, target, closureDelegate);
        return target;
    }

    private static <T> void configureTarget(Closure configureClosure, T target, ConfigureDelegate closureDelegate) {
        if (!(configureClosure instanceof GeneratedClosure)) {
            new ClosureBackedAction<T>(configureClosure, Closure.DELEGATE_FIRST, false).execute(target);
            return;
        }

        // Hackery to make closure execution faster, by short-circuiting the expensive property and method lookup on Closure
        Closure withNewOwner = configureClosure.rehydrate(target, closureDelegate, configureClosure.getThisObject());
        new ClosureBackedAction<T>(withNewOwner, Closure.OWNER_ONLY, false).execute(target);
    }

    public static class WrappedConfigureAction<T> implements Action<T> {
        private final Closure configureClosure;

        WrappedConfigureAction(Closure configureClosure) {
            this.configureClosure = configureClosure;
        }

        @Override
        public void execute(T t) {
            configure(configureClosure, t);
        }

        public Closure getConfigureClosure() {
            return configureClosure;
        }
    }
}
