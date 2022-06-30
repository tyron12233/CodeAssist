package com.tyron.builder.api.plugins;

import com.tyron.builder.api.tasks.Internal;

/**
 * Objects that can be extended at runtime with other objects.
 *
 * <pre class='autoTested'>
 * // Extensions are just plain objects, there is no interface/type
 * class MyExtension {
 *   String foo
 *
 *   MyExtension(String foo) {
 *     this.foo = foo
 *   }
 * }
 *
 * // Add new extensions via the extension container
 * project.extensions.create('custom', MyExtension, "bar")
 * //                       («name»,   «type»,       «constructor args», …)
 *
 * // extensions appear as properties on the target object by the given name
 * assert project.custom instanceof MyExtension
 * assert project.custom.foo == "bar"
 *
 * // also via a namespace method
 * project.custom {
 *   assert foo == "bar"
 *   foo = "other"
 * }
 * assert project.custom.foo == "other"
 *
 * // Extensions added with the extension container's create method are themselves extensible
 * assert project.custom instanceof ExtensionAware
 * project.custom.extensions.create("nested", MyExtension, "baz")
 * assert project.custom.nested.foo == "baz"
 *
 * // All extension aware objects have a special “ext” extension of type ExtraPropertiesExtension
 * assert project.hasProperty("myProperty") == false
 * project.ext.myProperty = "myValue"
 *
 * // Properties added to the “ext” extension are promoted to the owning object
 * assert project.myProperty == "myValue"
 * </pre>
 *
 * Many Gradle objects are extension aware. This includes; projects, tasks, configurations, dependencies etc.
 * <p>
 * For more on adding &amp; creating extensions, see {@link ExtensionContainer}.
 * <p>
 * For more on extra properties, see {@link ExtraPropertiesExtension}.
 * <p>
 * An <code>ExtensionAware</code> object has several 'scopes' that Gradle searches for properties. These scopes are:</p>
 *
 * <ul>
 * <li>The object itself. This scope includes any property getters and setters declared by the
 * implementation class. The properties of this scope are readable or writable depending on the presence
 * of the corresponding getter or setter method.</li>
 *
 * <li>Groovy Meta-programming methods implemented by the object's class, like <code>propertyMissing()</code>. Care must be taken by plugin authors to
 * ensure <code>propertyMissing()</code> is implemented such that if a property is not found a MissingPropertyException(String, Class) exception is thrown.
 * If <code>propertyMissing()</code> always returns a value for any property, <em>Gradle will not search the rest of the scopes below.</em></li>
 *
 * <li>The <em>extra</em> properties of the object.  Each object maintains a map of extra properties, which
 * can contain any arbitrary name -&gt; value pair.  Once defined, the properties of this scope are readable and writable.</li>
 *
 * <li>The <em>extensions</em> added to the object by plugins. Each extension is available as a read-only property with the same name as the extension.</li>
 * </ul>
 */
public interface ExtensionAware {

    /**
     * The container of extensions.
     */
    @Internal
    ExtensionContainer getExtensions();

}

