package com.tyron.builder.api.internal.model;

import com.tyron.builder.api.DomainObjectSet;
import com.tyron.builder.api.ExtensiblePolymorphicDomainObjectContainer;
import com.tyron.builder.api.Named;
import com.tyron.builder.api.NamedDomainObjectContainer;
import com.tyron.builder.api.NamedDomainObjectFactory;
import com.tyron.builder.api.NamedDomainObjectList;
import com.tyron.builder.api.NamedDomainObjectSet;
import com.tyron.builder.api.file.ConfigurableFileCollection;
import com.tyron.builder.api.file.ConfigurableFileTree;
import com.tyron.builder.api.file.DirectoryProperty;
import com.tyron.builder.api.file.RegularFileProperty;
import com.tyron.builder.api.file.SourceDirectorySet;
import com.tyron.builder.api.internal.provider.DefaultProperty;
import com.tyron.builder.api.internal.provider.PropertyHost;
import com.tyron.builder.api.model.ObjectFactory;
import com.tyron.builder.api.provider.ListProperty;
import com.tyron.builder.api.provider.MapProperty;
import com.tyron.builder.api.provider.Property;
import com.tyron.builder.api.provider.SetProperty;
import com.tyron.builder.api.reflect.ObjectInstantiationException;
import com.tyron.builder.internal.reflect.Instantiator;

public class InstantiatorBackedObjectFactory implements ObjectFactory {
    private final Instantiator instantiator;

    public InstantiatorBackedObjectFactory(Instantiator instantiator) {
        this.instantiator = instantiator;
    }

    @Override
    public <T extends Named> T named(Class<T> type, String name) throws ObjectInstantiationException {
        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing named objects");
    }

    @Override
    public SourceDirectorySet sourceDirectorySet(String name, String displayName) {
        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing source directory sets");
    }

    @Override
    public ConfigurableFileCollection fileCollection() {
        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing file collections");
    }

    @Override
    public ConfigurableFileTree fileTree() {
        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing file trees");
    }

    @Override
    public <T> NamedDomainObjectContainer<T> domainObjectContainer(Class<T> elementType) {
        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing named domain object containers");
    }

    @Override
    public <T> NamedDomainObjectContainer<T> domainObjectContainer(Class<T> elementType, NamedDomainObjectFactory<T> factory) {
        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing named domain object containers");
    }

    @Override
    public <T> ExtensiblePolymorphicDomainObjectContainer<T> polymorphicDomainObjectContainer(Class<T> elementType) {
        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing named domain object containers");
    }

//    @Override
//    public <T> DomainObjectSet<T> domainObjectSet(Class<T> elementType) {
//        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing domain object sets");
//    }

//    @Override
//    public <T> NamedDomainObjectSet<T> namedDomainObjectSet(Class<T> elementType) {
//        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing named domain object sets");
//    }
//
//    @Override
//    public <T> NamedDomainObjectList<T> namedDomainObjectList(Class<T> elementType) {
//        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing named domain object lists");
//    }

    @Override
    public <T> Property<T> property(Class<T> valueType) {
        return new DefaultProperty<>(PropertyHost.NO_OP, valueType);
    }

    @Override
    public <T> ListProperty<T> listProperty(Class<T> elementType) {
        return broken();
    }

    @Override
    public <T> SetProperty<T> setProperty(Class<T> elementType) {
        return broken();
    }

    @Override
    public <K, V> MapProperty<K, V> mapProperty(Class<K> keyType, Class<V> valueType) {
        return broken();
    }

    @Override
    public DirectoryProperty directoryProperty() {
        return broken();
    }

    @Override
    public RegularFileProperty fileProperty() {
        return broken();
    }

    private <T> T broken() {
        throw new UnsupportedOperationException("This ObjectFactory implementation does not support constructing property objects");
    }

    @Override
    public <T> T newInstance(Class<? extends T> type, Object... parameters) throws ObjectInstantiationException {
        return instantiator.newInstance(type, parameters);
    }
}
