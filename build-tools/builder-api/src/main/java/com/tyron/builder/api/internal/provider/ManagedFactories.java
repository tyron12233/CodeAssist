package com.tyron.builder.api.internal.provider;

import com.google.common.base.Objects;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.state.ManagedFactory;
import com.tyron.builder.api.providers.ListProperty;
import com.tyron.builder.api.providers.MapProperty;
import com.tyron.builder.api.providers.Property;
import com.tyron.builder.api.providers.Provider;
import com.tyron.builder.api.providers.SetProperty;

import org.jetbrains.annotations.Nullable;

import java.util.Map;

public class ManagedFactories {
    public static class ProviderManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = Provider.class;
        private static final Class<?> IMPL_TYPE = Providers.FixedValueProvider.class;
        public static final int FACTORY_ID = Objects.hashCode(IMPL_TYPE.getName());

        @Nullable
        @Override
        public <T> T fromState(Class<T> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            return type.cast(Providers.ofNullable(state));
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }

    public static class PropertyManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = Property.class;
        private static final Class<?> IMPL_TYPE = DefaultProperty.class;
        public static final int FACTORY_ID = Objects.hashCode(IMPL_TYPE.getName());
        private final PropertyFactory propertyFactory;

        public PropertyManagedFactory(PropertyFactory propertyFactory) {
            this.propertyFactory = propertyFactory;
        }

        @Nullable
        @Override
        public <S> S fromState(Class<S> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            // TODO - should preserve the property type (which may be different to the provider type)
            ProviderInternal<S> provider = Cast.uncheckedNonnullCast(state);
            return type.cast(propertyOf(provider.getType(), provider));
        }

        <V> Property<V> propertyOf(Class<V> type, Provider<V> value) {
            return propertyFactory.property(type).value(value);
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }

    public static class ListPropertyManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = ListProperty.class;
        private static final Class<?> IMPL_TYPE = DefaultListProperty.class;
        public static final int FACTORY_ID = Objects.hashCode(IMPL_TYPE.getName());
        private final PropertyFactory propertyFactory;

        public ListPropertyManagedFactory(PropertyFactory propertyFactory) {
            this.propertyFactory = propertyFactory;
        }

        @Nullable
        @Override
        public <S> S fromState(Class<S> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            // TODO - should preserve the element type
            DefaultListProperty<Object> property = propertyFactory.listProperty(Object.class);
            property.set(Cast.<Iterable<Object>>uncheckedNonnullCast(state));
            return type.cast(property);
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }

    public static class SetPropertyManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = SetProperty.class;
        private static final Class<?> IMPL_TYPE = DefaultSetProperty.class;
        public static final int FACTORY_ID = Objects.hashCode(IMPL_TYPE.getName());

        private final PropertyFactory propertyFactory;

        public SetPropertyManagedFactory(PropertyFactory propertyFactory) {
            this.propertyFactory = propertyFactory;
        }

        @Nullable
        @Override
        public <T> T fromState(Class<T> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            // TODO - should preserve the element type
            DefaultSetProperty<Object> property = propertyFactory.setProperty(Object.class);
            property.set(Cast.<Iterable<Object>>uncheckedNonnullCast(state));
            return type.cast(property);
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }

    public static class MapPropertyManagedFactory implements ManagedFactory {
        private static final Class<?> PUBLIC_TYPE = MapProperty.class;
        private static final Class<?> IMPL_TYPE = MapProperty.class;
        public static final int FACTORY_ID = Objects.hashCode(IMPL_TYPE.getName());

        private final PropertyFactory propertyFactory;

        public MapPropertyManagedFactory(PropertyFactory propertyFactory) {
            this.propertyFactory = propertyFactory;
        }

        @Nullable
        @Override
        public <S> S fromState(Class<S> type, Object state) {
            if (!type.isAssignableFrom(PUBLIC_TYPE)) {
                return null;
            }
            // TODO - should preserve the key and value types
            DefaultMapProperty<Object, Object> property = propertyFactory.mapProperty(Object.class, Object.class);
            property.set(Cast.<Map<Object, Object>>uncheckedNonnullCast(state));
            return type.cast(property);
        }

        @Override
        public int getId() {
            return FACTORY_ID;
        }
    }
}