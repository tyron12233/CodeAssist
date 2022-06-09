package com.tyron.builder.api.internal.model;

import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.ExtensiblePolymorphicDomainObjectContainer;
import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.api.NamedDomainObjectFactory;
import com.tyron.builder.api.NamedDomainObjectList;
import com.tyron.builder.api.NamedDomainObjectSet;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.api.file.SourceDirectorySet;
import com.tyron.builder.api.internal.collections.DomainObjectCollectionFactory;
//import com.tyron.builder.api.internal.file.DefaultSourceDirectorySet;
import com.tyron.builder.api.internal.file.DefaultSourceDirectorySet;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.file.collections.DirectoryFileTreeFactory;
import com.tyron.builder.api.internal.provider.PropertyFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ListProperty;
import com.tyron.builder.api.provider.MapProperty;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.provider.SetProperty;
import com.tyron.builder.api.reflect.ObjectInstantiationException;
import com.tyron.builder.api.tasks.util.PatternSet;
import com.tyron.builder.internal.Cast;
import com.tyron.builder.internal.Factory;
import com.tyron.builder.internal.reflect.Instantiator;
import com.tyron.builder.internal.reflect.JavaReflectionUtil;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultObjectFactory implements ObjectFactory {
    private final Instantiator instantiator;
    private final NamedObjectInstantiator namedObjectInstantiator;
    private final DirectoryFileTreeFactory directoryFileTreeFactory;
    private final Factory<PatternSet> patternSetFactory;
    private final PropertyFactory propertyFactory;
    private final FilePropertyFactory filePropertyFactory;
    private final FileCollectionFactory fileCollectionFactory;
    private final DomainObjectCollectionFactory domainObjectCollectionFactory;

    public DefaultObjectFactory(Instantiator instantiator, NamedObjectInstantiator namedObjectInstantiator, DirectoryFileTreeFactory directoryFileTreeFactory, Factory<PatternSet> patternSetFactory,
                                PropertyFactory propertyFactory, FilePropertyFactory filePropertyFactory, FileCollectionFactory fileCollectionFactory, DomainObjectCollectionFactory domainObjectCollectionFactory) {
        this.instantiator = instantiator;
        this.namedObjectInstantiator = namedObjectInstantiator;
        this.directoryFileTreeFactory = directoryFileTreeFactory;
        this.patternSetFactory = patternSetFactory;
        this.propertyFactory = propertyFactory;
        this.filePropertyFactory = filePropertyFactory;
        this.fileCollectionFactory = fileCollectionFactory;
        this.domainObjectCollectionFactory = domainObjectCollectionFactory;
    }

    @Override
    public <T extends Named> T named(final Class<T> type, final String name) {
        return namedObjectInstantiator.named(type, name);
    }

    @Override
    public <T> T newInstance(Class<? extends T> type, Object... parameters) throws ObjectInstantiationException {
        return instantiator.newInstance(type, parameters);
    }

    @Override
    public ConfigurableFileCollection fileCollection() {
        return fileCollectionFactory.configurableFiles();
    }

    @Override
    public ConfigurableFileTree fileTree() {
        return fileCollectionFactory.fileTree();
    }

    @Override
    public SourceDirectorySet sourceDirectorySet(final String name, final String displayName) {
        return new DefaultSourceDirectorySet(name, displayName, patternSetFactory, fileCollectionFactory, directoryFileTreeFactory, DefaultObjectFactory.this);
    }

    @Override
    public DirectoryProperty directoryProperty() {
        return filePropertyFactory.newDirectoryProperty();
    }

    @Override
    public RegularFileProperty fileProperty() {
        return filePropertyFactory.newFileProperty();
    }

    @Override
    public <T> NamedDomainObjectContainer<T> domainObjectContainer(Class<T> elementType) {
        return domainObjectCollectionFactory.newNamedDomainObjectContainer(elementType);
    }

    @Override
    public <T> NamedDomainObjectContainer<T> domainObjectContainer(Class<T> elementType, NamedDomainObjectFactory<T> factory) {
        return domainObjectCollectionFactory.newNamedDomainObjectContainer(elementType, factory);
    }

    @Override
    public <T> ExtensiblePolymorphicDomainObjectContainer<T> polymorphicDomainObjectContainer(Class<T> elementType) {
        return domainObjectCollectionFactory.newPolymorphicDomainObjectContainer(elementType);
    }

//    @Override
//    public <T> NamedDomainObjectSet<T> namedDomainObjectSet(Class<T> elementType) {
//        return domainObjectCollectionFactory.newNamedDomainObjectSet(elementType);
//    }
//
//    @Override
//    public <T> NamedDomainObjectList<T> namedDomainObjectList(Class<T> elementType) {
//        return domainObjectCollectionFactory.newNamedDomainObjectList(elementType);
//    }
//
//    @Override
//    public <T> DomainObjectSet<T> domainObjectSet(Class<T> elementType) {
//        return domainObjectCollectionFactory.newDomainObjectSet(elementType);
//    }

    @Override
    public <T> Property<T> property(Class<T> valueType) {
        if (valueType == null) {
            throw new IllegalArgumentException("Class cannot be null");
        }

        if (valueType.isPrimitive()) {
            // Kotlin passes these types for its own basic types
            return Cast.uncheckedNonnullCast(property(JavaReflectionUtil.getWrapperTypeForPrimitiveType(valueType)));
        }
        if (List.class.isAssignableFrom(valueType)) {
            throw new InvalidUserCodeException(invalidPropertyCreationError("listProperty()", "List<T>"));
        } else if (Set.class.isAssignableFrom(valueType)) {
            throw new InvalidUserCodeException(invalidPropertyCreationError("setProperty()", "Set<T>"));
        } else if (Map.class.isAssignableFrom(valueType)) {
            throw new InvalidUserCodeException(invalidPropertyCreationError("mapProperty()", "Map<K, V>"));
        } else if (Directory.class.isAssignableFrom(valueType)) {
            throw new InvalidUserCodeException(invalidPropertyCreationError("directoryProperty()", "Directory"));
        } else if (RegularFile.class.isAssignableFrom(valueType)) {
            throw new InvalidUserCodeException(invalidPropertyCreationError("fileProperty()", "RegularFile"));
        }

        return propertyFactory.property(valueType);
    }

    private String invalidPropertyCreationError(String correctMethodName, String propertyType) {
        return "Please use the ObjectFactory." + correctMethodName + " method to create a property of type " + propertyType + ".";
    }

    @Override
    public <T> ListProperty<T> listProperty(Class<T> elementType) {
        if (elementType.isPrimitive()) {
            // Kotlin passes these types for its own basic types
            return Cast.uncheckedNonnullCast(listProperty(JavaReflectionUtil.getWrapperTypeForPrimitiveType(elementType)));
        }
        return propertyFactory.listProperty(elementType);
    }

    @Override
    public <T> SetProperty<T> setProperty(Class<T> elementType) {
        if (elementType.isPrimitive()) {
            // Kotlin passes these types for its own basic types
            return Cast.uncheckedNonnullCast(setProperty(JavaReflectionUtil.getWrapperTypeForPrimitiveType(elementType)));
        }
        return propertyFactory.setProperty(elementType);
    }

    @Override
    public <K, V> MapProperty<K, V> mapProperty(Class<K> keyType, Class<V> valueType) {
        if (keyType.isPrimitive()) {
            // Kotlin passes these types for its own basic types
            return Cast.uncheckedNonnullCast(mapProperty(JavaReflectionUtil.getWrapperTypeForPrimitiveType(keyType), valueType));
        }
        if (valueType.isPrimitive()) {
            // Kotlin passes these types for its own basic types
            return Cast.uncheckedNonnullCast(mapProperty(keyType, JavaReflectionUtil.getWrapperTypeForPrimitiveType(valueType)));
        }
        return propertyFactory.mapProperty(keyType, valueType);
    }
}
