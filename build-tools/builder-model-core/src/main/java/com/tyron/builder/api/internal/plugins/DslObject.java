package com.tyron.builder.api.internal.plugins;

import com.tyron.builder.api.internal.ConventionMapping;
import com.tyron.builder.api.internal.DynamicObjectAware;
import com.tyron.builder.api.internal.GeneratedSubclasses;
import com.tyron.builder.api.internal.IConventionAware;
import com.tyron.builder.api.plugins.Convention;
import com.tyron.builder.api.plugins.ExtensionAware;
import com.tyron.builder.api.plugins.ExtensionContainer;
import com.tyron.builder.api.reflect.HasPublicType;
import com.tyron.builder.api.reflect.TypeOf;
import com.tyron.builder.internal.metaobject.DynamicObject;

import static com.tyron.builder.internal.Cast.uncheckedCast;

/**
 * Provides a unified, typed, interface to an enhanced DSL object.
 *
 * This is intended to be used with objects that have been decorated by the class generator.
 * <p>
 * Accessing each “aspect” of a DSL object may fail (with an {@link IllegalStateException}) if the DSL
 * object does not have that functionality. For example, calling {@link #getConventionMapping()} will fail
 * if the backing object does not implement {@link IConventionAware}.
 */
@SuppressWarnings("deprecation")
public class DslObject implements DynamicObjectAware, ExtensionAware, IConventionAware, com.tyron.builder.api.internal.HasConvention {

    private DynamicObject dynamicObject;
    private ExtensionContainer extensionContainer;
    private ConventionMapping conventionMapping;
    private Convention convention;

    private final Object object;

    public DslObject(Object object) {
        this.object = object;
    }

    @Override
    public DynamicObject getAsDynamicObject() {
        if (dynamicObject == null) {
            this.dynamicObject = toType(object, DynamicObjectAware.class).getAsDynamicObject();
        }
        return dynamicObject;
    }

    @Override
    @Deprecated
    public Convention getConvention() {
        if (convention == null) {
            this.convention = toType(object, com.tyron.builder.api.internal.HasConvention.class).getConvention();
        }
        return convention;
    }

    @Override
    public ExtensionContainer getExtensions() {
        if (extensionContainer == null) {
            this.extensionContainer = toType(object, ExtensionAware.class).getExtensions();
        }
        return extensionContainer;
    }

    @Override
    public ConventionMapping getConventionMapping() {
        if (conventionMapping == null) {
            this.conventionMapping = toType(object, IConventionAware.class).getConventionMapping();
        }
        return conventionMapping;
    }

    public Class<?> getDeclaredType() {
        return getPublicType().getConcreteClass();
    }

    public TypeOf<Object> getPublicType() {
        if (object instanceof HasPublicType) {
            return uncheckedCast(((HasPublicType) object).getPublicType());
        }
        return TypeOf.<Object>typeOf(GeneratedSubclasses.unpackType(object));
    }

    public Class<?> getImplementationType() {
        return GeneratedSubclasses.unpackType(object);
    }

    private static <T> T toType(Object delegate, Class<T> type) {
        if (type.isInstance(delegate)) {
            return type.cast(delegate);
        } else {
            throw new IllegalStateException(
                    String.format("Cannot create DslObject for '%s' (class: %s) as it does not implement '%s' (it is not a DSL object)",
                            delegate, delegate.getClass().getSimpleName(), type.getName())
            );
        }
    }

}
