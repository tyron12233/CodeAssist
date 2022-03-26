package com.tyron.builder.api.internal.provider;


import com.google.common.base.Preconditions;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.provider.ValueSupplier.ExecutionTimeValue;
import com.tyron.builder.api.internal.provider.ValueSupplier.Value;
import com.tyron.builder.api.providers.Property;
import com.tyron.builder.api.providers.Provider;

import org.graalvm.compiler.lir.ValueConsumer;
import org.jetbrains.annotations.Nullable;

public class DefaultProperty<T> extends AbstractProperty<T, ProviderInternal<? extends T>> implements Property<T> {
    private final Class<T> type;
    private final ValueSanitizer<T> sanitizer;

    public DefaultProperty(PropertyHost propertyHost, Class<T> type) {
        super(propertyHost);
        this.type = type;
        this.sanitizer = ValueSanitizers.forType(type);
        init(Providers.notDefined());
    }

    @Override
    public Object unpackState() {
        return getProvider();
    }

    @Override
    public Class<?> publicType() {
        return Property.class;
    }

    @Override
    public int getFactoryId() {
        return ManagedFactories.PropertyManagedFactory.FACTORY_ID;
    }

    @Override
    public Class<T> getType() {
        return type;
    }

    @Override
    public void setFromAnyValue(Object object) {
        if (object instanceof Provider) {
            set(Cast.<Provider<T>>uncheckedNonnullCast(object));
        } else {
            set(Cast.<T>uncheckedNonnullCast(object));
        }
    }

    @Override
    public void set(T value) {
        if (value == null) {
            discardValue();
        } else {
            setSupplier(Providers.fixedValue(getValidationDisplayName(), value, type, sanitizer));
        }
    }

    @Override
    public Property<T> value(@Nullable T value) {
        set(value);
        return this;
    }

    @Override
    public Property<T> value(Provider<? extends T> provider) {
        set(provider);
        return this;
    }

    public ProviderInternal<? extends T> getProvider() {
        return getSupplier();
    }

    public DefaultProperty<T> provider(Provider<? extends T> provider) {
        set(provider);
        return this;
    }

    @Override
    public void set(Provider<? extends T> provider) {
        Preconditions.checkArgument(provider != null, "Cannot set the value of a property using a null provider.");
        ProviderInternal<? extends T> p = Providers.internal(provider);
        setSupplier(p.asSupplier(getValidationDisplayName(), type, sanitizer));
    }

    @Override
    public Property<T> convention(@Nullable T value) {
        if (value == null) {
            setConvention(Providers.notDefined());
        } else {
            setConvention(Providers.fixedValue(getValidationDisplayName(), value, type, sanitizer));
        }
        return this;
    }

    @Override
    public Property<T> convention(Provider<? extends T> provider) {
        Preconditions.checkArgument(provider != null, "Cannot set the convention of a property using a null provider.");
        setConvention(Providers.internal(provider).asSupplier(getValidationDisplayName(), type, sanitizer));
        return this;
    }

    @Override
    protected ExecutionTimeValue<? extends T> calculateOwnExecutionTimeValue(ProviderInternal<? extends T> value) {
        // Discard this property from a provider chain, as it does not contribute anything to the calculation.
        return value.calculateExecutionTimeValue();
    }

    @Override
    protected Value<? extends T> calculateValueFrom(ProviderInternal<? extends T> value, ValueConsumer consumer) {
        return value.calculateValue(consumer);
    }

    @Override
    protected ProviderInternal<? extends T> finalValue(ProviderInternal<? extends T> value, ValueConsumer consumer) {
        return value.withFinalValue(consumer);
    }

    @Override
    protected String describeContents() {
        // NOTE: Do not realize the value of the Provider in toString().  The debugger will try to call this method and make debugging really frustrating.
        return String.format("property(%s, %s)", type.getName(), getSupplier());
    }
}