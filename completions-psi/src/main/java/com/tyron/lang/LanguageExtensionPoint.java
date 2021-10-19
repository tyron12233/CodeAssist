package com.tyron.lang;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.kotlin.com.intellij.lang.Language;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.CustomLoadingExtensionPointBean;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.kotlin.com.intellij.util.KeyedLazyInstance;
import org.jetbrains.kotlin.com.intellij.util.xmlb.annotations.Attribute;

/**
 * Base class for {@link Language}-bound extension points.
 */
public class LanguageExtensionPoint<T> extends CustomLoadingExtensionPointBean<T>  implements KeyedLazyInstance<T> {

    // these must be public for scrambling compatibility
    /**
     * Language ID.
     *
     * @see Language#getID()
     */
    @Attribute("language")
    public String language;

    @Attribute("implementationClass")
    public String implementationClass;

    /**
     * @deprecated You must pass plugin descriptor, use {@link LanguageExtensionPoint#LanguageExtensionPoint(String, Object)}
     */
    @Deprecated
    public LanguageExtensionPoint() {
    }

    @TestOnly
    public LanguageExtensionPoint(@NotNull String language, @NotNull String implementationClass, @NotNull PluginDescriptor pluginDescriptor) {
        this.language = language;
        this.implementationClass = implementationClass;
        setPluginDescriptor(pluginDescriptor);
    }

    @TestOnly
    public LanguageExtensionPoint(@NotNull String language, @NotNull T instance) {
        super(instance);

        this.language = language;
        implementationClass = instance.getClass().getName();
    }

    @Nullable
    @Override
    protected final String getImplementationClassName() {
        return implementationClass;
    }

    @Override
    public String getKey() {
        return language;
    }
}
