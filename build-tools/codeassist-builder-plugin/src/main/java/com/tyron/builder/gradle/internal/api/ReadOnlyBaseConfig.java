package com.tyron.builder.gradle.internal.api;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.tyron.builder.model.BaseConfig;
import com.tyron.builder.model.ClassField;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import groovy.lang.GroovyObject;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.MissingPropertyException;
import java.io.File;
import java.util.Collection;
import java.util.Map;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;

/**
 * Read-only version of the BaseConfig wrapping another BaseConfig.
 *
 * <p>In the variant API, it is important that the objects returned by the variants are read-only.
 *
 * <p>However, even though the API is defined to use the base interfaces as return type (which all
 * contain only getters), the dynamics of Groovy makes it easy to actually use the setters of the
 * implementation classes.
 *
 * <p>This wrapper ensures that the returned instance is actually just a strict implementation of the
 * base interface and is read-only.
 */
public abstract class ReadOnlyBaseConfig extends GroovyObjectSupport implements BaseConfig {

    @NonNull
    private BaseConfig baseConfig;

    protected ReadOnlyBaseConfig(@NonNull BaseConfig baseConfig) {
        this.baseConfig = baseConfig;
    }

    @NonNull
    @Override
    public String getName() {
        return baseConfig.getName();
    }

    @Nullable
    @Override
    public String getApplicationIdSuffix() {
        return baseConfig.getApplicationIdSuffix();
    }

    @Nullable
    @Override
    public String getVersionNameSuffix() {
        return baseConfig.getVersionNameSuffix();
    }

    @NonNull
    @Override
    public Map<String, ClassField> getBuildConfigFields() {
        // TODO: cache immutable map?
        return ImmutableMap.copyOf(baseConfig.getBuildConfigFields());
    }

    @NonNull
    @Override
    public Map<String, ClassField> getResValues() {
        return ImmutableMap.copyOf(baseConfig.getResValues());
    }

    @NonNull
    @Override
    public Collection<File> getProguardFiles() {
        return ImmutableList.copyOf(baseConfig.getProguardFiles());
    }

    @NonNull
    @Override
    public Collection<File> getConsumerProguardFiles() {
        return ImmutableList.copyOf(baseConfig.getConsumerProguardFiles());
    }

    @NonNull
    @Override
    public Collection<File> getTestProguardFiles() {
        return ImmutableList.copyOf(baseConfig.getTestProguardFiles());
    }

    @NonNull
    @Override
    public Map<String, Object> getManifestPlaceholders() {
        return ImmutableMap.copyOf(baseConfig.getManifestPlaceholders());
    }

    @Nullable
    @Override
    public Boolean getMultiDexEnabled() {
        return baseConfig.getMultiDexEnabled();
    }

    @Nullable
    @Override
    public File getMultiDexKeepFile() {
        return baseConfig.getMultiDexKeepFile();
    }

    @Nullable
    @Override
    public File getMultiDexKeepProguard() {
        return baseConfig.getMultiDexKeepProguard();
    }

    /**
     * Some build scripts add dynamic properties to flavors declaration (and others) and expect to
     * retrieve such properties values through this model. Delegate any property we don't know about
     * to the {@link BaseConfig} groovy object which hopefully will know about the dynamic property.
     *
     * @param name the property name
     * @return the property value if exists or an exception will be thrown.
     */
    @SuppressWarnings("unused") // This is part of the Groovy method dispatch convention.
    public Object propertyMissing(final String name) {
        try {
            return ((GroovyObject) baseConfig).getProperty(name);
        } catch (MissingPropertyException e) {
            // do not leak implementation types, replace the delegate with ourselves in the message
            throw new MissingPropertyException("Could not find " + name + " on " + this);
        }

    }

    /**
     * Do not authorize setting dynamic properties values and provide a meaningful error message.
     */
    @SuppressWarnings("unused") // This is part of the Groovy method dispatch convention.
    public void propertyMissing(String name, Object value) {
        throw new RuntimeException(
                String.format(
                        "Cannot set property %s on read-only %s.",
                        name,
                        baseConfig.getClass().getName()));
    }

    @SuppressWarnings("unused") // This is part of the Groovy/Gradle method dispatch convention.
    public boolean hasProperty(String name) {
        if (DefaultGroovyMethods.hasProperty(this, name) != null) {
            return true;
        } else {
            GroovyObject groovyObject = (GroovyObject) this.baseConfig;
            // Object _Decorated by Gradle implement hasProperty, so the "usual" Groovy conventions
            // don't apply.
            return (Boolean) groovyObject.invokeMethod("hasProperty", name);
        }
    }
}
