package com.tyron.builder.api.model.internal;

import com.tyron.builder.api.Named;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.api.internal.file.ConfigurableFileCollection;
import com.tyron.builder.api.internal.file.FileCollectionFactory;
import com.tyron.builder.api.internal.file.FilePropertyFactory;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.providers.ListProperty;
import com.tyron.builder.api.providers.MapProperty;
import com.tyron.builder.api.providers.Property;
import com.tyron.builder.api.providers.SetProperty;
import com.tyron.builder.api.reflect.ObjectInstantiationException;

public class DefaultObjectFactory implements ObjectFactory {

    private final FilePropertyFactory filePropertyFactory;
    private final FileCollectionFactory fileCollectionFactory;

    public DefaultObjectFactory(FileCollectionFactory fileCollectionFactory, FilePropertyFactory filePropertyFactory) {
        this.fileCollectionFactory = fileCollectionFactory;
        this.filePropertyFactory = filePropertyFactory;
    }

    @Override
    public <T extends Named> T named(Class<T> type,
                                     String name) throws ObjectInstantiationException {
        return null;
    }

    @Override
    public <T> T newInstance(Class<? extends T> type,
                             Object... parameters) throws ObjectInstantiationException {
        return null;
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
        return null;
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
