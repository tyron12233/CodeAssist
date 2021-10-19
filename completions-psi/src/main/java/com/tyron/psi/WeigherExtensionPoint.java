package com.tyron.psi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.com.intellij.openapi.components.ComponentManager;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPointName;
import org.jetbrains.kotlin.com.intellij.openapi.extensions.PluginDescriptor;
import org.jetbrains.kotlin.com.intellij.serviceContainer.BaseKeyedLazyInstance;
import org.jetbrains.kotlin.com.intellij.util.KeyedLazyInstance;
import org.jetbrains.kotlin.com.intellij.util.xmlb.annotations.Attribute;

/**
 * @author peter
 */
public final class WeigherExtensionPoint extends BaseKeyedLazyInstance<Weigher> implements KeyedLazyInstance<Weigher> {
    public static final ExtensionPointName<WeigherExtensionPoint> EP = new ExtensionPointName<>("com.intellij.weigher");

    // these must be public for scrambling compatibility
    @Attribute("key")
    public String key;

    @Attribute("implementationClass")
    public String implementationClass;

    @Attribute("id")
    public String id;

    @Override
    protected @Nullable String getImplementationClassName() {
        return implementationClass;
    }

    @Override
    public @NotNull Weigher createInstance(@NotNull ComponentManager componentManager, @NotNull PluginDescriptor pluginDescriptor) {
        Weigher weigher = super.createInstance(componentManager, pluginDescriptor);
        weigher.setDebugName(id);
        return weigher;
    }

    @Override
    public String getKey() {
        return key;
    }
}
