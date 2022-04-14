package com.tyron.builder.api.model.internal;

import com.tyron.builder.api.InvalidUserCodeException;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.Directory;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.RegularFile;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.api.internal.Cast;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.internal.provider.PropertyFactory;
import com.tyron.builder.api.internal.reflect.DirectInstantiator;
import com.tyron.builder.api.internal.reflect.JavaReflectionUtil;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.providers.ListProperty;
import com.tyron.builder.api.providers.MapProperty;
import com.tyron.builder.api.providers.Property;
import com.tyron.builder.api.providers.SetProperty;
import com.tyron.builder.api.reflect.ObjectInstantiationException;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultObjectFactory implements ObjectFactory {

    private final FilePropertyFactory filePropertyFactory;
    private final FileCollectionFactory fileCollectionFactory;
    private final PropertyFactory propertyFactory;

    public DefaultObjectFactory(FileCollectionFactory fileCollectionFactory, FilePropertyFactory filePropertyFactory, PropertyFactory propertyFactory) {
        this.fileCollectionFactory = fileCollectionFactory;
        this.filePropertyFactory = filePropertyFactory;
        this.propertyFactory = propertyFactory;
    }

    @Override
    public <T extends Named> T named(Class<T> type,
                                     String name) throws ObjectInstantiationException {
        return null;
    }

    @Override
    public <T> T newInstance(Class<? extends T> type,
                             Object... parameters) throws ObjectInstantiationException {
        return DirectInstantiator.instantiate(type, parameters);
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
        return null;
    }

    @Override
    public <T> SetProperty<T> setProperty(Class<T> elementType) {
        return null;
    }

    @Override
    public <K, V> MapProperty<K, V> mapProperty(Class<K> keyType, Class<V> valueType) {
        return null;
    }

    @Override
    public DirectoryProperty directoryProperty() {
        return filePropertyFactory.newDirectoryProperty();
    }

    @Override
    public RegularFileProperty fileProperty() {
        return filePropertyFactory.newFileProperty();
    }
}
