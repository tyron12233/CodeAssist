package com.tyron.builder.internal.extensibility;

import com.tyron.builder.internal.Cast;
import com.tyron.builder.api.plugins.ExtensionsSchema;

import java.util.Iterator;

public class DefaultExtensionsSchema implements ExtensionsSchema {

    public static ExtensionsSchema create(Iterable<? extends ExtensionSchema> schemas) {
        return new DefaultExtensionsSchema(Cast.uncheckedCast(schemas));
    }

    private final Iterable<ExtensionSchema> extensionSchemas;

    private DefaultExtensionsSchema(Iterable<ExtensionSchema> extensionSchemas) {
        this.extensionSchemas = extensionSchemas;
    }

    @Override
    public Iterator<ExtensionSchema> iterator() {
        return extensionSchemas.iterator();
    }

    @Override
    public Iterable<ExtensionSchema> getElements() {
        return this;
    }
}

