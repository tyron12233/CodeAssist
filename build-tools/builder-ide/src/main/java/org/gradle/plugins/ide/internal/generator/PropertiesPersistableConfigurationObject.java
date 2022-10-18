package org.gradle.plugins.ide.internal.generator;

import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import org.gradle.api.Action;
import org.gradle.api.internal.PropertiesTransformer;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import static org.gradle.util.internal.ConfigureUtil.configureUsing;

public abstract class PropertiesPersistableConfigurationObject extends AbstractPersistableConfigurationObject {

    private final PropertiesTransformer transformer;
    private Properties properties;

    protected PropertiesPersistableConfigurationObject(PropertiesTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public void load(InputStream inputStream) throws Exception {
        properties = new Properties();
        properties.load(inputStream);
        load(properties);
    }

    @Override
    public void store(OutputStream outputStream) {
        store(properties);
        transformer.transform(properties, outputStream);
    }

    protected abstract void store(Properties properties);

    protected abstract void load(Properties properties);

    public void transformAction(@DelegatesTo(Properties.class) Closure action) {
        transformAction(configureUsing(action));
    }

    /**
     * @param action transform action
     * @since 3.5
     */
    public void transformAction(Action<? super Properties> action) {
        transformer.addAction(action);
    }
}