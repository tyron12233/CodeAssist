package com.tyron.builder.api.plugins;

import com.tyron.builder.api.Task;
import com.tyron.builder.api.BuildProject;
import com.tyron.builder.internal.metaobject.DynamicObject;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * <p>A {@code Convention} manages a set of <i>convention objects</i>. When you add a convention object to a {@code
 * Convention}, and the properties and methods of the convention object become available as properties and methods of
 * the object which the convention is associated to. A convention object is simply a POJO or POGO. Usually, a {@code
 * Convention} is used by plugins to extend a {@link BuildProject} or a {@link Task}.</p>
 */
public interface Convention extends ExtensionContainer {

    /**
     * Returns the plugin convention objects contained in this convention.
     *
     * @return The plugins. Returns an empty map when this convention does not contain any convention objects.
     */
    Map<String, Object> getPlugins();

    /**
     * Locates the plugin convention object with the given type.
     *
     * @param type The convention object type.
     * @return The object. Never returns null.
     * @throws IllegalStateException When there is no such object contained in this convention, or when there are
     * multiple such objects.
     */
    <T> T getPlugin(Class<T> type) throws IllegalStateException;

    /**
     * Locates the plugin convention object with the given type.
     *
     * @param type The convention object type.
     * @return The object. Returns null if there is no such object.
     * @throws IllegalStateException When there are multiple matching objects.
     */
    @Nullable
    <T> T findPlugin(Class<T> type) throws IllegalStateException;

    /**
     * Returns a dynamic object which represents the properties and methods contributed by the extensions and convention objects contained in this
     * convention.
     *
     * @return The dynamic object
     */
    DynamicObject getExtensionsAsDynamicObject();
}

