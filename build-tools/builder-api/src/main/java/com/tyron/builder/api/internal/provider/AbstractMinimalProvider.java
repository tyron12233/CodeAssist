package com.tyron.builder.api.internal.provider;

import com.tyron.builder.api.Transformer;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.DisplayName;
import com.tyron.builder.api.internal.logging.TreeFormatter;
import com.tyron.builder.api.internal.state.Managed;
import com.tyron.builder.api.internal.tasks.TaskDependencyResolveContext;
import com.tyron.builder.api.providers.Provider;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A partial {@link Provider} implementation. Subclasses must implement {@link ProviderInternal#getType()} and {@link AbstractMinimalProvider#calculateOwnValue(ValueConsumer)}.
 */
public abstract class AbstractMinimalProvider<T> implements ProviderInternal<T>, Managed {
    private static final DisplayName DEFAULT_DISPLAY_NAME = new DisplayName() {
        @Override
        public String getCapitalizedDisplayName() {
            return "this provider".toUpperCase(Locale.ROOT);
        }

        @Override
        public String getDisplayName() {
            return "this provider"  ;
        }
    };

    @Override
    public <S> ProviderInternal<S> map(final Transformer<? extends S, ? super T> transformer) {
        return new TransformBackedProvider<>(transformer, this);
    }

    @Override
    public <S> Provider<S> flatMap(final Transformer<? extends Provider<? extends S>, ? super T> transformer) {
        return new FlatMapProvider<>(this, transformer);
    }

    /**
     * Returns the human consumable display name for this provider, or null if this is not known.
     */
    @Nullable
    protected DisplayName getDeclaredDisplayName() {
        return null;
    }

    /**
     * Returns a display name for this provider, using a default if this is not known.
     */
    protected DisplayName getDisplayName() {
        DisplayName displayName = getDeclaredDisplayName();
        if (displayName == null) {
            return DEFAULT_DISPLAY_NAME;
        }
        return displayName;
    }

    protected DisplayName getTypedDisplayName() {
        return DEFAULT_DISPLAY_NAME;
    }

    protected abstract ValueSupplier.Value<? extends T> calculateOwnValue(ValueConsumer consumer);

    @Override
    public boolean isPresent() {
        return calculatePresence(ValueConsumer.IgnoreUnsafeRead);
    }

    @Override
    public boolean calculatePresence(ValueConsumer consumer) {
        return !calculateOwnValue(consumer).isMissing();
    }

    @Override
    public T get() {
        Value<? extends T> value = calculateOwnValue(ValueConsumer.IgnoreUnsafeRead);
        if (value.isMissing()) {
            throw new MissingValueException(cannotQueryValueOf(value));
        }
        return value.get();
    }

    @Override
    public T getOrNull() {
        return calculateOwnValue(ValueConsumer.IgnoreUnsafeRead).orNull();
    }

    @Override
    public T getOrElse(T defaultValue) {
        return calculateOwnValue(ValueConsumer.IgnoreUnsafeRead).orElse(defaultValue);
    }

    @Override
    public Value<? extends T> calculateValue(ValueConsumer consumer) {
        return calculateOwnValue(consumer).pushWhenMissing(getDeclaredDisplayName());
    }

    @Override
    public Provider<T> orElse(T value) {
        return new OrElseFixedValueProvider<>(this, value);
    }

    @Override
    public Provider<T> orElse(Provider<? extends T> provider) {
        return new OrElseProvider<>(this, Providers.internal(provider));
    }

    @Deprecated
    @Override
    public final Provider<T> forUseAtConfigurationTime() {
        /*
 TODO:configuration-cache start nagging in Gradle 8.x
        DeprecationLogger.deprecateMethod(Provider.class, "forUseAtConfigurationTime")
            .withAdvice("Simply remove the call.")
            .willBeRemovedInGradle9()
            .withUpgradeGuideSection(7, "for_use_at_configuration_time_deprecation")
            .nagUser();
*/
        return this;
    }

    @Override
    public void visitDependencies(TaskDependencyResolveContext context) {
        // When used as an input, add the producing tasks if known
        getProducer().visitProducerTasks(context);
    }

    @Override
    public ValueProducer getProducer() {
        return ValueProducer.unknown();
    }

    @Override
    public ExecutionTimeValue<? extends T> calculateExecutionTimeValue() {
        return ExecutionTimeValue.value(calculateOwnValue(ValueConsumer.IgnoreUnsafeRead));
    }

    @Override
    public ProviderInternal<T> asSupplier(DisplayName owner, Class<? super T> targetType, ValueSanitizer<? super T> sanitizer) {
        if (getType() != null && !targetType.isAssignableFrom(getType())) {
            throw new IllegalArgumentException(String.format("Cannot set the value of %s of type %s using a provider of type %s.", owner.getDisplayName(), targetType.getName(), getType().getName()));
        } else if (getType() == null) {
            return new MappingProvider<>(Cast.uncheckedCast(targetType), this, new TypeSanitizingTransformer<>(owner, sanitizer, targetType));
        } else {
            return this;
        }
    }

    @Override
    public ProviderInternal<T> withFinalValue(ValueConsumer consumer) {
        return Providers.nullableValue(calculateValue(consumer));
    }

    @Override
    public String toString() {
        // NOTE: Do not realize the value of the Provider in toString().  The debugger will try to call this method and make debugging really frustrating.
        Class<?> type = getType();
        return String.format("provider(%s)", type == null ? "?" : type.getName());
    }

    @Override
    public boolean isImmutable() {
        return false;
    }

    @Override
    public Class<?> publicType() {
        return Provider.class;
    }

    @Override
    public Object unpackState() {
        return getOrNull();
    }

    @Override
    public int getFactoryId() {
        return ManagedFactories.ProviderManagedFactory.FACTORY_ID;
    }

    private String cannotQueryValueOf(Value<? extends T> value) {
        TreeFormatter formatter = new TreeFormatter();
        formatter.node("Cannot query the value of ").append(getDisplayName().getDisplayName()).append(" because it has no value available.");
        if (!value.getPathToOrigin().isEmpty()) {
            formatter.node("The value of ").append(getTypedDisplayName().getDisplayName()).append(" is derived from");
            formatter.startChildren();
            for (DisplayName displayName : value.getPathToOrigin()) {
                formatter.node(displayName.getDisplayName());
            }
            formatter.endChildren();
        }
        return formatter.toString();
    }
}