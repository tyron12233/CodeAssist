package com.tyron.builder.internal.instantiation.generator;

import com.tyron.builder.api.Describable;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ListProperty;
import com.tyron.builder.api.provider.MapProperty;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.provider.SetProperty;
import com.tyron.builder.internal.DisplayName;
import com.tyron.builder.internal.instantiation.InstanceGenerator;
import com.tyron.builder.internal.instantiation.PropertyRoleAnnotationHandler;
import com.tyron.builder.internal.service.ServiceLookup;
import com.tyron.builder.internal.serialization.Cached;
import com.tyron.builder.internal.state.ModelObject;
import com.tyron.builder.internal.state.OwnerAware;

import org.apache.commons.lang3.StringUtils;

/**
 * A helper used by generated classes to create managed instances.
 */
public class ManagedObjectFactory {
    private final ServiceLookup serviceLookup;
    private final InstanceGenerator instantiator;
    private final PropertyRoleAnnotationHandler roleHandler;

    public ManagedObjectFactory(ServiceLookup serviceLookup, InstanceGenerator instantiator, PropertyRoleAnnotationHandler roleHandler) {
        this.serviceLookup = serviceLookup;
        this.instantiator = instantiator;
        this.roleHandler = roleHandler;
    }

    // Also called from generated code
    public static <T> T attachOwner(T instance, ModelObject owner, String propertyName) {
        if (instance instanceof OwnerAware) {
            ((OwnerAware) instance).attachOwner(owner, displayNameFor(owner, propertyName));
        }
        return instance;
    }

    // Called from generated code
    public void applyRole(Object value, ModelObject owner) {
        roleHandler.applyRoleTo(owner, value);
    }

    // Called from generated code
    public Object newInstance(ModelObject owner, String propertyName, Class<?> type) {
        if (type.isAssignableFrom(ConfigurableFileCollection.class)) {
            return attachOwner(getObjectFactory().fileCollection(), owner, propertyName);
        }
        if (type.isAssignableFrom(ConfigurableFileTree.class)) {
            return attachOwner(getObjectFactory().fileTree(), owner, propertyName);
        }
        if (type.isAssignableFrom(DirectoryProperty.class)) {
            return attachOwner(getObjectFactory().directoryProperty(), owner, propertyName);
        }
        if (type.isAssignableFrom(RegularFileProperty.class)) {
            return attachOwner(getObjectFactory().fileProperty(), owner, propertyName);
        }
        return attachOwner(instantiator.newInstanceWithDisplayName(type, displayNameFor(owner, propertyName)), owner, propertyName);
    }

    // Called from generated code
    public Object newInstance(ModelObject owner, String propertyName, Class<?> type, Class<?> paramType) {
        if (type.isAssignableFrom(Property.class)) {
            return attachOwner(getObjectFactory().property(paramType), owner, propertyName);
        }
        if (type.isAssignableFrom(ListProperty.class)) {
            return attachOwner(getObjectFactory().listProperty(paramType), owner, propertyName);
        }
        if (type.isAssignableFrom(SetProperty.class)) {
            return attachOwner(getObjectFactory().setProperty(paramType), owner, propertyName);
        }
//        if (type.isAssignableFrom(NamedDomainObjectContainer.class)) {
//            return attachOwner(getObjectFactory().domainObjectContainer(paramType), owner, propertyName);
//        }
//        if (type.isAssignableFrom(DomainObjectSet.class)) {
//            return attachOwner(getObjectFactory().domainObjectSet(paramType), owner, propertyName);
//        }
        throw new IllegalArgumentException("Don't know how to create an instance of type " + type.getName());
    }

    // Called from generated code
    public Object newInstance(ModelObject owner, String propertyName, Class<?> type, Class<?> keyType, Class<?> valueType) {
        if (type.isAssignableFrom(MapProperty.class)) {
            return attachOwner(getObjectFactory().mapProperty(keyType, valueType), owner, propertyName);
        }
        throw new IllegalArgumentException("Don't know how to create an instance of type " + type.getName());
    }

    private static ManagedPropertyName displayNameFor(ModelObject owner, String propertyName) {
        if (owner.getModelIdentityDisplayName() instanceof ManagedPropertyName) {
            ManagedPropertyName root = (ManagedPropertyName) owner.getModelIdentityDisplayName();
            return new ManagedPropertyName(root.ownerDisplayName, root.propertyName + "." + propertyName);
        } else {
            return new ManagedPropertyName(cachedOwnerDisplayNameOf(owner), propertyName);
        }
    }

    private static Cached<String> cachedOwnerDisplayNameOf(ModelObject owner) {
        return Cached.of(() -> {
            Describable ownerModelIdentityDisplayName = owner.getModelIdentityDisplayName();
            if (ownerModelIdentityDisplayName != null) {
                return ownerModelIdentityDisplayName.getDisplayName();
            }
            return null;
        });
    }

    private ObjectFactory getObjectFactory() {
        return (ObjectFactory) serviceLookup.get(ObjectFactory.class);
    }

    private static class ManagedPropertyName implements DisplayName {
        private final Cached<String> ownerDisplayName;
        private final String propertyName;

        public ManagedPropertyName(Cached<String> ownerDisplayName, String propertyName) {
            this.ownerDisplayName = ownerDisplayName;
            this.propertyName = propertyName;
        }

        @Override
        public String toString() {
            return getDisplayName();
        }

        @Override
        public String getCapitalizedDisplayName() {
            return StringUtils.capitalize(getDisplayName());
        }

        @Override
        public String getDisplayName() {
            if (ownerDisplayName.get() != null) {
                return ownerDisplayName.get() + " property '" + propertyName + "'";
            } else {
                return "property '" + propertyName + "'";
            }
        }
    }
}
